package com.example.auraflow.service

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.example.auraflow.R

/**
 * Keeps the user out of short-form video feeds:
 *  - Instagram: Home, the Explore grid, Reels and the reel player are covered by an overlay that
 *    stops above the bottom tab bar, so the user can still tap Messages or Profile. On Explore
 *    it also starts below the search box, so searching (and opening a result) still works. The
 *    service never navigates Instagram by itself (no deep links, no Back): Instagram resets to
 *    Home when it handles a deep link, which used to re-trigger the redirect in a loop. A reel
 *    opened from a chat shows its sender header and stays watchable; once the user swipes to a
 *    suggested reel the header is gone and the player is covered.
 *  - TikTok: the Home / For You feed is covered by an overlay (media muted behind it).
 *  - YouTube: the Shorts player is covered the same way.
 *
 * Instagram events are coalesced into one check per [INSTAGRAM_SETTLE_MS], which only looks up
 * a few view IDs (no tree walk). TikTok/YouTube actions start an [ACTION_COOLDOWN_MS] freeze so
 * nothing can stack. At most one overlay view exists.
 */
class ShortsBlockerService : AccessibilityService() {

    private enum class BlockedApp(
        @StringRes val title: Int,
        @StringRes val button: Int,
        val prefKey: String,
    ) {
        INSTAGRAM(R.string.overlay_title_instagram, R.string.overlay_button_instagram, BlockerSettings.INSTAGRAM),
        TIKTOK(R.string.overlay_title_tiktok, R.string.overlay_button_tiktok, BlockerSettings.TIKTOK),
        YOUTUBE(R.string.overlay_title_youtube, R.string.overlay_button_youtube, BlockerSettings.YOUTUBE),
    }

    /** Instagram bottom-nav tabs, keyed by their view IDs in the dumps. */
    private enum class InstagramTab(val viewId: String) {
        HOME("com.instagram.android:id/feed_tab"),
        REELS("com.instagram.android:id/clips_tab"),
        DIRECT("com.instagram.android:id/direct_tab"),
        SEARCH("com.instagram.android:id/search_tab"),
        PROFILE("com.instagram.android:id/profile_tab"),
    }

    private enum class InstagramVerdict {
        /** Home, Explore/Search, Reels, or the reel player without a chat's sender header. */
        BLOCK,
        /** Messages, Profile, a reel opened from a chat, or a screen without the tab bar. */
        ALLOW,
        /** The tab bar is up but no tab is selected yet (mid-transition): change nothing. */
        UNKNOWN,
    }

    private val prefs by lazy { BlockerSettings.prefs(this) }
    /** A field, not a lambda at the call site: SharedPreferences only holds listeners weakly. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // Turned off while its overlay is up: uncover the app right away.
        val app = BlockedApp.entries.firstOrNull { it.prefKey == key }
        if (app != null && overlayApp == app && !isBlockingEnabled(app)) removeOverlay()
    }

    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    /** When the last blocking action happened. Everything is ignored for [ACTION_COOLDOWN_MS] after. */
    private var lastActionTime = 0L
    /** TikTok/YouTube scans walk the tree, so they're limited to one per [SCAN_WINDOW_MS]. */
    private var lastScanAt = 0L

    // Instagram state.
    private val handler = Handler(Looper.getMainLooper())
    /** True while a check is posted; events arriving meanwhile are folded into it. */
    private var instagramCheckPending = false
    private val instagramCheck = Runnable {
        instagramCheckPending = false
        checkInstagram()
    }

    /** The one and only overlay view, or null. */
    private var overlay: View? = null
    private var overlayApp: BlockedApp? = null
    /** True only if we muted media ourselves, so we never unmute something the user muted. */
    private var mutedByUs = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val app = blockedAppFor(pkg)

        // The user switched apps (Home, recents, another app): never leave the overlay up.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            overlayApp != null && app != overlayApp && pkg != packageName && pkg != SYSTEM_UI_PACKAGE
        ) {
            removeOverlay()
        }

        // Freeze after a TikTok/YouTube action: no double redirects, no stacked overlays.
        // Instagram needs none: its overlay is idempotent and never navigates the app.
        if (app != BlockedApp.INSTAGRAM && System.currentTimeMillis() - lastActionTime < ACTION_COOLDOWN_MS) return

        when (app) {
            BlockedApp.INSTAGRAM -> scheduleInstagramCheck()
            BlockedApp.TIKTOK, BlockedApp.YOUTUBE -> handleOverlayApp(app, pkg, event)
            null -> Unit
        }
    }

    /** The app [pkg] belongs to, or null if it isn't one we block or the user switched it off. */
    private fun blockedAppFor(pkg: String): BlockedApp? = when (pkg) {
        INSTAGRAM_PACKAGE -> BlockedApp.INSTAGRAM
        YOUTUBE_PACKAGE -> BlockedApp.YOUTUBE
        in TIKTOK_PACKAGES -> BlockedApp.TIKTOK
        else -> null
    }?.takeIf(::isBlockingEnabled)

    private fun isBlockingEnabled(app: BlockedApp) = BlockerSettings.isEnabled(prefs, app.prefKey)

    /**
     * The app's window root. Not just [rootInActiveWindow]: once the user touches our overlay,
     * or the keyboard is up, the active window isn't the app's.
     */
    private fun rootFor(pkg: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName == pkg }
            ?: windows.firstNotNullOfOrNull { window -> window.root?.takeIf { it.packageName == pkg } }

    // ---- Instagram ----

    /**
     * Instagram floods content-change events (autoplay, story rings, counters), and every node
     * lookup runs on its UI thread. So events only post one check, [INSTAGRAM_SETTLE_MS] later;
     * anything arriving meanwhile is covered by it. The delay also lets a tapped tab finish
     * drawing before it's read.
     */
    private fun scheduleInstagramCheck() {
        if (instagramCheckPending) return
        instagramCheckPending = true
        handler.postDelayed(instagramCheck, INSTAGRAM_SETTLE_MS)
    }

    /** Shows or removes the overlay. Never navigates Instagram, so it can't cause new events. */
    private fun checkInstagram() {
        if (!isBlockingEnabled(BlockedApp.INSTAGRAM)) return
        val root = rootFor(INSTAGRAM_PACKAGE) ?: return // Not on screen any more.
        val bar = root.visibleView(INSTAGRAM_TAB_BAR_ID)
        val tab = bar?.selectedInstagramTab()
        // The Explore grid's top bar, holding the search box.
        val searchBar = if (tab == InstagramTab.SEARCH) root.visibleView(INSTAGRAM_EXPLORE_ACTION_BAR_ID) else null
        when (classifyInstagram(root, bar, tab, searchBar)) {
            // Between the search box (if any) and the tab bar, so both stay usable.
            InstagramVerdict.BLOCK -> showOverlay(
                BlockedApp.INSTAGRAM,
                top = searchBar?.screenBounds()?.bottom ?: 0,
                bottom = bar?.screenBounds()?.top,
            )
            InstagramVerdict.ALLOW -> if (overlayApp == BlockedApp.INSTAGRAM) removeOverlay()
            InstagramVerdict.UNKNOWN -> Unit
        }
    }

    /**
     * The reel player is checked first: on the Reels tab it sits above a visible tab bar. A reel
     * opened from a chat carries the sender header and reply box; a swipe to the next (suggested)
     * reel drops them. Elsewhere the selected tab decides: its labels are on every tab, so text
     * can't tell the screens apart. On the Search tab only the Explore grid ([searchBar] showing)
     * is a feed; a profile or page opened from a result is not. Without the bar (typing a search,
     * a chat thread, a story, settings, the camera) the screen is left alone: feeds always have it.
     */
    private fun classifyInstagram(
        root: AccessibilityNodeInfo,
        bar: AccessibilityNodeInfo?,
        tab: InstagramTab?,
        searchBar: AccessibilityNodeInfo?,
    ): InstagramVerdict {
        if (root.hasVisibleView(INSTAGRAM_REEL_PAGER_ID)) {
            return if (INSTAGRAM_DM_REEL_IDS.any { root.hasVisibleView(it) }) InstagramVerdict.ALLOW
            else InstagramVerdict.BLOCK
        }
        bar ?: return InstagramVerdict.ALLOW
        return when (tab) {
            InstagramTab.HOME, InstagramTab.REELS -> InstagramVerdict.BLOCK
            InstagramTab.SEARCH -> if (searchBar != null) InstagramVerdict.BLOCK else InstagramVerdict.ALLOW
            InstagramTab.DIRECT, InstagramTab.PROFILE -> InstagramVerdict.ALLOW
            null -> InstagramVerdict.UNKNOWN
        }
    }

    /** The selected tab of the bottom bar [this]. */
    private fun AccessibilityNodeInfo.selectedInstagramTab(): InstagramTab? {
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            if (child.isSelected) return InstagramTab.entries.firstOrNull { it.viewId == child.viewIdResourceName }
        }
        return null
    }

    /** Taps the Messages tab. Returns false if it isn't on screen, so the caller can fall back. */
    private fun clickInstagramMessagesTab(): Boolean {
        var target = rootFor(INSTAGRAM_PACKAGE)
            ?.findAccessibilityNodeInfosByViewId(InstagramTab.DIRECT.viewId)
            ?.firstOrNull { it.isVisibleToUser }
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // ---- TikTok / YouTube ----

    private fun handleOverlayApp(app: BlockedApp, pkg: String, event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            now - lastScanAt < SCAN_WINDOW_MS
        ) return
        lastScanAt = now
        val root = rootFor(pkg) ?: return

        val blocked = when (app) {
            BlockedApp.TIKTOK -> isTikTokFeed(root, pkg)
            BlockedApp.YOUTUBE -> isYouTubeShorts(root)
            BlockedApp.INSTAGRAM -> false
        }
        if (blocked) showOverlay(app) else if (overlayApp == app) removeOverlay()
    }

    private fun isYouTubeShorts(root: AccessibilityNodeInfo): Boolean =
        YOUTUBE_SHORTS_IDS.any { root.hasFullScreenView(it) }

    private fun redirectToSubscriptions() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_SUBSCRIPTIONS_URL))
            .setPackage(YOUTUBE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * The For You / Following feed is identified by the bottom nav (Home selected) or the
     * feed's top tabs. A full-screen video opened from a chat or profile has neither, so it
     * stays watchable.
     */
    private fun isTikTokFeed(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (root.hasVisibleView("$pkg:id/notification_page")) return false

        var homeSelected = false
        var feedTabs = false
        root.forEachNode { node ->
            if (!node.isVisibleToUser) return@forEachNode true
            if (node.isSelected && node.labelMatches("Home", exact = true)) homeSelected = true
            if (node.labelMatches("For You", exact = true)) feedTabs = true
            !(homeSelected || feedTabs)
        }
        if (homeSelected || feedTabs) return true

        return root.hasFullScreenView("$pkg:id/view_pager") &&
            root.hasVisibleView("$pkg:id/main_tab")
    }

    /** Taps the Inbox tab. Returns false if it couldn't be found, so the caller can fall back. */
    private fun switchTikTokToInbox(root: AccessibilityNodeInfo, pkg: String): Boolean {
        var inbox: AccessibilityNodeInfo? =
            root.findAccessibilityNodeInfosByViewId("$pkg:id/notification_page")
                .firstOrNull { it.isVisibleToUser }
        if (inbox == null) {
            root.forEachNode { node ->
                if (node.isVisibleToUser && node.labelMatches("Inbox", exact = true)) inbox = node
                inbox == null
            }
        }
        var target = inbox
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    // ---- Overlay (single instance) ----

    /**
     * Shows [app]'s overlay from screen y [top] down to [bottom] (null: the screen's bottom),
     * replacing any other one. Never adds a second view: if it's already up, only its span is
     * updated.
     */
    private fun showOverlay(app: BlockedApp, top: Int = 0, bottom: Int? = null) {
        val height = bottom?.minus(top)?.takeIf { it > 0 } ?: WindowManager.LayoutParams.MATCH_PARENT
        val current = overlay
        if (overlayApp == app && current != null) {
            val params = current.layoutParams as? WindowManager.LayoutParams ?: return
            if (params.y != top || params.height != height) {
                params.y = top
                params.height = height
                try {
                    windowManager.updateViewLayout(current, params)
                } catch (_: IllegalArgumentException) {
                    // Already detached.
                }
            }
            return
        }
        removeOverlay()

        val view = buildOverlayView(app)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, so system Back still reaches the app and can navigate away; touches
            // below a shortened overlay (Instagram's tab bar) go to the app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = top
        }
        try {
            windowManager.addView(view, params)
        } catch (_: RuntimeException) {
            return // Window token unavailable (service being torn down).
        }
        overlay = view
        overlayApp = app
        if (app != BlockedApp.INSTAGRAM) lastActionTime = System.currentTimeMillis()
        muteMedia()
    }

    /** Removes the overlay synchronously (no fade), so a stale view can never linger. */
    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        overlayApp = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // Already detached.
        }
        restoreMedia()
    }

    private fun onOverlayButton(app: BlockedApp) {
        if (app == BlockedApp.INSTAGRAM) {
            // The user asked, so navigating is safe. The overlay stays until the next check
            // sees Messages (or, from a reel player without the bar, the chat it came from).
            if (!clickInstagramMessagesTab()) performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        removeOverlay() // Also restores media volume.
        lastActionTime = System.currentTimeMillis()
        when (app) {
            BlockedApp.YOUTUBE -> redirectToSubscriptions()
            BlockedApp.TIKTOK -> {
                val pkg = TIKTOK_PACKAGES.firstOrNull { rootFor(it) != null }
                val root = pkg?.let(::rootFor)
                if (root == null || !switchTikTokToInbox(root, pkg)) performGlobalAction(GLOBAL_ACTION_BACK)
            }
            BlockedApp.INSTAGRAM -> Unit // Handled above.
        }
    }

    /**
     * A near-opaque scrim with a centered Material-style card: lock badge, title, message and a
     * button back to the app's safe screen. Only the card animates in.
     */
    private fun buildOverlayView(app: BlockedApp): View {
        val lockBadge = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList = ColorStateList.valueOf(OVERLAY_ACCENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(OVERLAY_ACCENT_CONTAINER)
            }
        }
        val title = TextView(this).apply {
            setText(app.title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        val subtitle = TextView(this).apply {
            setText(R.string.overlay_message_focus)
            setTextColor(OVERLAY_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        }
        val button = TextView(this).apply {
            setText(app.button)
            setTextColor(OVERLAY_ON_ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                setColor(OVERLAY_ACCENT)
                cornerRadius = dp(28).toFloat()
            }
            isClickable = true
            setOnClickListener { onOverlayButton(app) }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(32), dp(28), dp(28))
            background = GradientDrawable().apply {
                setColor(OVERLAY_CARD)
                cornerRadius = dp(28).toFloat()
            }
            elevation = dp(6).toFloat()
            addView(lockBadge, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(title)
            addView(subtitle)
            addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            // Starts on the first frame after the overlay is attached.
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(OVERLAY_CARD_IN_MS).start()
        }
        return FrameLayout(this).apply {
            setBackgroundColor(OVERLAY_SCRIM)
            // Swallow touches so the feed underneath can't be scrolled.
            isClickable = true
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ).apply { setMargins(dp(24), 0, dp(24), 0) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---- Audio ----

    /** Mutes media behind the overlay, unless on a call or the user already muted it. */
    private fun muteMedia() {
        if (mutedByUs) return
        val mode = audioManager.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return
        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) return
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            mutedByUs = true
        } catch (_: SecurityException) {
            // Some Do Not Disturb configurations refuse volume changes; the overlay still works.
        }
    }

    private fun restoreMedia() {
        if (!mutedByUs) return
        mutedByUs = false
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: SecurityException) {
        }
    }

    // ---- Node helpers ----

    private fun AccessibilityNodeInfo.visibleView(viewId: String): AccessibilityNodeInfo? =
        findAccessibilityNodeInfosByViewId(viewId).firstOrNull { it.isVisibleToUser }

    private fun AccessibilityNodeInfo.hasVisibleView(viewId: String): Boolean = visibleView(viewId) != null

    /** True if [viewId] is visible and fills most of the screen, i.e. a player, not a shelf/preview. */
    private fun AccessibilityNodeInfo.hasFullScreenView(viewId: String): Boolean {
        val screen = screenBounds()
        return findAccessibilityNodeInfosByViewId(viewId)
            .any { it.isVisibleToUser && it.coversMostOf(screen) }
    }

    private fun AccessibilityNodeInfo.coversMostOf(screen: Rect): Boolean {
        val bounds = screenBounds()
        return bounds.height() >= screen.height() * FULL_SCREEN_FRACTION &&
            bounds.width() >= screen.width() * FULL_SCREEN_FRACTION
    }

    private fun AccessibilityNodeInfo.screenBounds(): Rect = Rect().also(::getBoundsInScreen)

    private fun AccessibilityNodeInfo.labelMatches(label: String, exact: Boolean): Boolean =
        listOfNotNull(text, contentDescription).any {
            if (exact) it.toString().trim().equals(label, ignoreCase = true)
            else it.contains(label, ignoreCase = true)
        }

    /** Breadth-first walk, capped so a huge tree can't stall the main thread. Stop by returning false. */
    private inline fun AccessibilityNodeInfo.forEachNode(visit: (AccessibilityNodeInfo) -> Boolean) {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(this@forEachNode) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES_SCANNED) {
            val node = queue.removeFirst()
            if (!visit(node)) return
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacks(instagramCheck)
        removeOverlay()
        super.onDestroy()
    }

    private companion object {
        /** Freeze after any action. */
        const val ACTION_COOLDOWN_MS = 1_000L
        const val SCAN_WINDOW_MS = 200L
        /** Delay of the one Instagram check that covers every event in between. */
        const val INSTAGRAM_SETTLE_MS = 150L
        const val MAX_NODES_SCANNED = 1_500
        const val FULL_SCREEN_FRACTION = 0.6f
        /** Notification shade / heads-up windows shouldn't dismiss the overlay. */
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        const val OVERLAY_CARD_IN_MS = 180L
        const val OVERLAY_SCRIM = 0xEE121212.toInt()
        const val OVERLAY_CARD = 0xFF1E1F22.toInt()
        const val OVERLAY_TEXT_SECONDARY = 0xFFB4B8BF.toInt()
        const val OVERLAY_ACCENT = 0xFFA8C7FA.toInt()
        const val OVERLAY_ACCENT_CONTAINER = 0x33A8C7FA
        const val OVERLAY_ON_ACCENT = 0xFF062E6F.toInt()

        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val YOUTUBE_SUBSCRIPTIONS_URL = "https://www.youtube.com/feed/subscriptions"
        val YOUTUBE_SHORTS_IDS = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_watch_player",
        )

        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val INSTAGRAM_TAB_BAR_ID = "com.instagram.android:id/tab_bar"
        /** Top bar of the Explore grid with the search box ("exploremain" dump); typing a search
         *  opens a separate screen without it or the tab bar ("exploresearch" dump). */
        const val INSTAGRAM_EXPLORE_ACTION_BAR_ID = "com.instagram.android:id/explore_action_bar"
        /** The vertical pager in the reel player (from the dumps). */
        const val INSTAGRAM_REEL_PAGER_ID = "com.instagram.android:id/clips_viewer_view_pager"
        /**
         * Sender header and reply box on a reel opened from a chat ("sent reels" dump); both are
         * gone after a swipe to the next reel ("reels2" dump).
         */
        val INSTAGRAM_DM_REEL_IDS = listOf(
            "com.instagram.android:id/sender_username_or_fullname",
            "com.instagram.android:id/reel_viewer_message_composer",
        )

        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",    // TikTok (global)
            "com.ss.android.ugc.trill",    // TikTok (some regions)
            "com.zhiliaoapp.musically.go", // TikTok Lite
        )
    }
}
