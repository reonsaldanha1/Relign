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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import ai.relign.app.ui.theme.TextSecondary
import ai.relign.app.ui.theme.TextTertiary

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
    var currentTab by remember { mutableStateOf(NavTab.SHIELD) }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                    onNavigateToRules = { currentTab = NavTab.RULES }
                )
                NavTab.RULES -> RulesScreen(prefs = prefs)
                NavTab.INSIGHTS -> InsightsScreen(prefs = prefs)
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
    }
}
