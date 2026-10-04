package ai.relign.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.relign.app.service.RelignAccessibilityService
import ai.relign.app.ui.screens.DashboardScreen
import ai.relign.app.ui.screens.InsightsScreen
import ai.relign.app.ui.screens.RitualScreen
import ai.relign.app.ui.screens.RulesScreen
import ai.relign.app.ui.theme.EmeraldPrimary
import ai.relign.app.ui.theme.HairlineBorder
import ai.relign.app.ui.theme.ObsidianBase
import ai.relign.app.ui.theme.RelignTheme
import ai.relign.app.ui.theme.SurfaceContainer
import ai.relign.app.ui.theme.SurfaceContainerLow
import ai.relign.app.ui.theme.TextPrimary
import ai.relign.app.ui.theme.TextTertiary
import ai.relign.app.util.AccessibilityUtil

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = RelignApplication.instance.preferencesManager

        setContent {
            RelignTheme {
                MainScreen(prefs = prefs)
            }
        }
    }
}

enum class NavTab(val title: String, val icon: ImageVector) {
    SHIELD("Shield", Icons.Rounded.Security),
    RULES("Rules", Icons.Rounded.Tune),
    INSIGHTS("Insights", Icons.Rounded.AutoGraph),
    RITUAL("Ritual", Icons.Rounded.Spa)
}

@Composable
fun MainScreen(prefs: ai.relign.app.data.PreferencesManager) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(NavTab.SHIELD) }
    var showTestModal by remember { mutableStateOf(false) }

    // Live reactive state for Accessibility Service & Usage Access
    val isRunningFlow by RelignAccessibilityService.isServiceRunning.collectAsState()
    var isSettingsPermissionGranted by remember {
        mutableStateOf(AccessibilityUtil.isServiceEnabled(context))
    }
    var resumeCounter by remember { mutableStateOf(0) }

    // Refresh immediately when returning from Settings (ON_RESUME)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isSettingsPermissionGranted = AccessibilityUtil.isServiceEnabled(context)
                resumeCounter++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isServiceConnected = isRunningFlow || isSettingsPermissionGranted

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(ObsidianBase)
    ) {
        // Screen Content
        Crossfade(
            targetState = currentTab,
            label = "tab_crossfade",
            modifier = Modifier.fillMaxSize()
        ) { tab ->
            when (tab) {
                NavTab.SHIELD -> DashboardScreen(
                    prefs = prefs,
                    isServiceConnected = isServiceConnected,
                    resumeKey = resumeCounter,
                    onNavigateToRules = { currentTab = NavTab.RULES },
                    onTestShield = { showTestModal = true },
                    onRefreshStatus = {
                        isSettingsPermissionGranted = AccessibilityUtil.isServiceEnabled(context)
                        resumeCounter++
                    }
                )
                NavTab.RULES -> RulesScreen(prefs = prefs)
                NavTab.INSIGHTS -> InsightsScreen(prefs = prefs, resumeKey = resumeCounter)
                NavTab.RITUAL -> RitualScreen()
            }
        }

        // Floating Bottom Navigation Bar (Stitch Specification)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9999.dp))
                    .background(SurfaceContainerLow)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(9999.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(if (isSelected) SurfaceContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { currentTab = tab }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) EmeraldPrimary else TextTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = tab.title,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // In-App Test Overlay (Doesn't crash or minimize MainActivity!)
        if (showTestModal) {
            Box(modifier = Modifier.fillMaxSize()) {
                ai.relign.app.ui.MindfulPauseScreen(
                    targetApp = "YouTube Shorts (Demo Test)",
                    reason = "Doomscroll Intervention Test",
                    canBypass = true,
                    onCloseApp = {
                        prefs.recordMindfulSave()
                        showTestModal = false
                    },
                    onIntentionalPass = {
                        prefs.recordIntentionalPass(5)
                        showTestModal = false
                    }
                )
            }
        }
    }
}
