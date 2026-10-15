package com.example.auraflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.auraflow.service.BlockerSettings
import com.example.auraflow.service.ShortsBlockerService
import com.example.auraflow.ui.theme.AuraFlowTheme

/** One row in the "Blocking rules" card. */
data class BlockRule(
    val prefKey: String,
    val name: String,
    val detail: String,
    val badge: String,
    val badgeColor: Color,
)

val BLOCK_RULES = listOf(
    BlockRule(BlockerSettings.INSTAGRAM, "Instagram", "Home, Reels and the Explore grid are covered. DMs, Profile and Search stay open; a reel a friend sends gets one view.", "IG", Color(0xFFD62976)),
    BlockRule(BlockerSettings.TIKTOK, "TikTok", "For You and Following feeds. Inbox and profile stay open.", "TT", Color(0xFF25F4EE)),
    BlockRule(BlockerSettings.YOUTUBE, "YouTube", "The Shorts player. Regular videos and Subscriptions stay open.", "YT", Color(0xFFFF0033)),
)

class MainActivity : ComponentActivity() {

    private var serviceEnabled by mutableStateOf(false)
    private val ruleStates = mutableStateMapOf<String, Boolean>()

    /** Keeps the status badge live while the screen is visible, even in split screen. */
    private val accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            serviceEnabled = isShortsBlockerEnabled(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = BlockerSettings.prefs(this)
        BLOCK_RULES.forEach { ruleStates[it.prefKey] = BlockerSettings.isEnabled(prefs, it.prefKey) }
        setContent {
            AuraFlowTheme {
                FocusBlockerScreen(
                    serviceEnabled = serviceEnabled,
                    ruleStates = ruleStates,
                    onManageAccessibility = ::openAccessibilitySettings,
                    onRuleToggled = { key, enabled ->
                        ruleStates[key] = enabled
                        BlockerSettings.setEnabled(this, key, enabled)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            accessibilityObserver,
        )
        // The user may have just come back from Accessibility Settings.
        serviceEnabled = isShortsBlockerEnabled(this)
    }

    override fun onStop() {
        contentResolver.unregisterContentObserver(accessibilityObserver)
        super.onStop()
    }

    private fun openAccessibilitySettings() {
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun isShortsBlockerEnabled(context: Context): Boolean {
    val expected = ComponentName(context, ShortsBlockerService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
        .any { ComponentName.unflattenFromString(it) == expected }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusBlockerScreen(
    serviceEnabled: Boolean,
    ruleStates: Map<String, Boolean>,
    onManageAccessibility: () -> Unit,
    onRuleToggled: (String, Boolean) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("FocusBlocker", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(serviceEnabled, onManageAccessibility)
            RulesCard(ruleStates, onRuleToggled)
            Text(
                "Screen contents are read only to spot these feeds. Nothing is stored or sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val CardShape = RoundedCornerShape(24.dp)
private val StatusOn = Color(0xFF2E7D32)
private val StatusOff = Color(0xFFC62828)

@Composable
private fun StatusCard(serviceEnabled: Boolean, onManageAccessibility: () -> Unit) {
    val badgeColor by animateColorAsState(if (serviceEnabled) StatusOn else StatusOff, label = "badge")
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (serviceEnabled) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Blocker service",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Surface(shape = CircleShape, color = badgeColor.copy(alpha = 0.16f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(badgeColor))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (serviceEnabled) "ON" else "OFF",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                        )
                    }
                }
            }
            Text(
                if (serviceEnabled) {
                    "Blocking is active. Your rules below apply right away."
                } else {
                    "Android only lets accessibility services be turned on from system settings. " +
                        "Find \"AuraFlow Shorts Blocker\" there and switch it on."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onManageAccessibility, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (serviceEnabled) "Manage Accessibility" else "Grant Accessibility")
            }
        }
    }
}

@Composable
private fun RulesCard(ruleStates: Map<String, Boolean>, onRuleToggled: (String, Boolean) -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Blocking rules",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        BLOCK_RULES.forEachIndexed { index, rule ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            val checked = ruleStates[rule.prefKey] ?: true
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(rule.badgeColor.copy(alpha = 0.18f)),
                ) {
                    Text(
                        rule.badge,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        rule.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = checked, onCheckedChange = { onRuleToggled(rule.prefKey, it) })
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Preview(showBackground = true)
@Composable
fun FocusBlockerScreenPreview() {
    AuraFlowTheme(darkTheme = true, dynamicColor = false) {
        FocusBlockerScreen(
            serviceEnabled = true,
            ruleStates = mapOf(BlockerSettings.TIKTOK to false),
            onManageAccessibility = {},
            onRuleToggled = { _, _ -> },
        )
    }
}
