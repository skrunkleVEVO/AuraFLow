# AuraFLow

A lightweight Android focus utility that keeps you out of short-form video feeds — Instagram
Reels and Home, TikTok For You, YouTube Shorts — while leaving the parts of those apps you
actually need (messages, profiles, search) fully usable.

No root, no VPN, no network access. AuraFlow runs as an Android Accessibility Service,
reads only the view IDs it needs to recognise a feed, and stores nothing but three on/off
switches.

## Key features

**Instagram**
- **Feeds covered:** Home, the Reels tab, the Explore grid, and the full-screen reel player.
- **Safe zones stay open:** Messages (inbox and chat threads), your Profile, and Search. On
  Explore the overlay starts below the search box, so you can still search for people and
  open their profiles.
- **Tab bar stays live:** the overlay stops above Instagram's bottom bar, so you can tap
  Messages or Profile directly. The **Open DMs** button does the same for you.
- **One-pass friend reels:** a reel a friend sends you in a chat plays normally. Swipe to the
  next (suggested) reel and the player is covered; press Back to return to the chat.

**TikTok:** the For You / Following feed is covered; Inbox and profiles stay open.

**YouTube:** the Shorts player is covered; regular videos and Subscriptions stay open.

Media is muted while an overlay is up (never during a call, and never unmuted if you muted it
yourself). Each app can be switched off individually in the AuraFlow settings screen.

## How it works

### Accessibility Service
`ShortsBlockerService` (`app/src/main/java/com/example/auraflow/service/`) receives window
events from the target apps and inspects their view hierarchy. Detection is based on
**resource view IDs** (e.g. `com.instagram.android:id/tab_bar`), not on text, so it works in
any language.

### Recognising Instagram screens
Instagram is effectively a single activity that swaps fragments, so the foreground activity
class can't tell Home from Messages. The service instead reads a handful of stable view IDs,
taken from real window dumps:

| Screen | Signal | Result |
|---|---|---|
| Reel player with sender header / reply box | `clips_viewer_view_pager` + `sender_username_or_fullname` or `reel_viewer_message_composer` | Allowed (friend pass) |
| Any other reel player | `clips_viewer_view_pager` | Covered |
| Home / Reels tab | `tab_bar` with `feed_tab` / `clips_tab` selected | Covered |
| Explore grid | `search_tab` selected + `explore_action_bar` visible | Covered below the search box |
| Search results, profiles opened from search | `search_tab` selected, no Explore bar | Allowed |
| Messages / Profile tab | `direct_tab` / `profile_tab` selected | Allowed |
| No tab bar (typing a search, chat thread, story, settings, camera) | — | Allowed |
| Tab bar but no tab selected yet (mid-transition) | — | No change |

### Why an overlay, not redirects
Early versions bounced the user with `GLOBAL_ACTION_BACK` or an `instagram://direct` deep
link. Both change Instagram's navigation from the outside: handling a deep link makes
Instagram rebuild Home first, Back from a top-level tab lands on Home, and either one
re-triggers the blocker. The result was an event feedback loop: flicker, the feed jumping
to the top, and stacked activity windows.

FocusBlocker therefore **never navigates the app on its own**. It only shows or hides a
single overlay window (`TYPE_ACCESSIBILITY_OVERLAY`). Showing it twice is a no-op, and it
produces no navigation events, so a loop is impossible by construction. Navigation only
happens when *you* tap something.

### Staying fast
Accessibility node queries are answered on the target app's UI thread, so careless scanning
makes the *target* app janky. FocusBlocker keeps the cost bounded:
- `notificationTimeout="100"` lets Android merge bursts of content-change events.
- Instagram events are **coalesced**: the first event posts one check 150 ms later, and every
  event in between is covered by it, so there are at most ~7 checks per second however much
  Instagram animates.
- Each check is about four direct view-ID lookups. There is no recursive tree walk.
- The overlay only appears or disappears on a positive signal; transitional frames change
  nothing, so it doesn't flicker.

## Installation & usage

1. **Install the APK.** Copy the APK (see *Building* below) to your phone and open it. Allow
   installing from that source if Android asks.
2. **Open FocusBlocker** and tap **Grant Accessibility**.
3. In Accessibility settings, open **Installed apps → AuraFlow Shorts Blocker** and turn it on.
   - On Android 13+, a sideloaded app's accessibility switch may be greyed out ("Restricted
     setting"). Go to **Settings → Apps → AuraFlow → ⋮ → Allow restricted settings**, then
     try again.
4. Back in FocusBlocker, choose which apps to block. All three are on by default.

To pause blocking, switch an app off in FocusBlocker or turn the service off in Accessibility
settings.

**Privacy:** the service reads screen structure only to recognise feeds. Nothing is logged,
stored, or sent anywhere, and the app requests no internet permission.

## Building

Requirements: Android Studio (or JDK 17+ with the Android SDK), compile SDK 37, min SDK 24.

```sh
./gradlew assembleDebug     # installable, debug-signed: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # optimised, unsigned: app/build/outputs/apk/release/app-release-unsigned.apk
```

The release build has no signing config. Android won't install an unsigned APK, so to
distribute it, sign it with your own key (Android Studio: **Build → Generate Signed App
Bundle / APK**, or add a `signingConfig` to `app/build.gradle.kts`).

## Project layout

```
app/src/main/java/com/example/auraflow/
├── MainActivity.kt                 Settings screen (Compose): permission status and per-app switches
└── service/
    ├── ShortsBlockerService.kt     Accessibility service: detection and overlay
    └── BlockerSettings.kt          Per-app on/off switches (SharedPreferences)
app/src/main/res/xml/accessibility_service_config.xml   Event types, flags, notificationTimeout
```
