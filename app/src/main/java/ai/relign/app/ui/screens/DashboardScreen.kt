package ai.relign.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ai.relign.app.ui.MindfulPauseActivity
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.relign.app.data.PreferencesManager
import ai.relign.app.service.RelignAccessibilityService
import ai.relign.app.ui.theme.EmeraldDark
import ai.relign.app.ui.theme.EmeraldPrimary
import ai.relign.app.ui.theme.EmeraldSecondary
import ai.relign.app.ui.theme.EmeraldTertiary
import ai.relign.app.ui.theme.HairlineBorder
import ai.relign.app.ui.theme.ObsidianBase
import ai.relign.app.ui.theme.SurfaceContainer
import ai.relign.app.ui.theme.SurfaceContainerHigh
import ai.relign.app.ui.theme.SurfaceContainerLow
import ai.relign.app.ui.theme.TextPrimary
import ai.relign.app.ui.theme.TextSecondary
import ai.relign.app.ui.theme.TextTertiary
import ai.relign.app.ui.theme.WarningPeach

@Composable
fun DashboardScreen(
    prefs: PreferencesManager,
    isServiceConnected: Boolean,
    onNavigateToRules: () -> Unit,
    onRefreshStatus: () -> Unit = {}
) {
    val context = LocalContext.current
    val isShieldActive by prefs.isShieldActive.collectAsState()
    val isBlockShorts by prefs.isBlockShortsEnabled.collectAsState()
    val isAllowFirstShorts by prefs.isAllowFirstShortsEnabled.collectAsState()
    val mindfulSaves by prefs.mindfulSavesCount.collectAsState()
    val intentionalPasses by prefs.intentionalPassesCount.collectAsState()
    val timeSavedMinutes by prefs.timeSavedMinutes.collectAsState()

    val totalInterventions = mindfulSaves + intentionalPasses
    val closePercent = if (totalInterventions > 0) (mindfulSaves * 100) / totalInterventions else 0
    val passPercent = if (totalInterventions > 0) (intentionalPasses * 100) / totalInterventions else 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Relign",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Mindful Digital Sanctuary",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            // Shield Active Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9999.dp))
                    .background(SurfaceContainerLow)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(9999.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isShieldActive && isServiceConnected) EmeraldPrimary else WarningPeach)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isShieldActive && isServiceConnected) "Shield Active" else "Shield Paused",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Accessibility Permission Notice if not connected
        if (!isServiceConnected) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .border(1.dp, WarningPeach.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = WarningPeach,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Accessibility Shield Inactive",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Required to detect Shorts and block channels",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Step 1: Open Accessibility Settings
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = ObsidianBase
                        ),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Text(
                            text = "1. Enable in Accessibility Settings",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Step 2: Open App Info for Restricted Settings
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = TextSecondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Android 13+: Allow Restricted Settings",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "💡 Tip: If the switch is grayed out in Accessibility, tap button #2 above → tap 3 dots (⋮) in top-right → tap 'Allow restricted settings', then return to step 1.",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { onRefreshStatus() },
                        border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        shape = RoundedCornerShape(9999.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = TextSecondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Refresh Status Check", fontSize = 11.sp)
                        }
                    }
                }
            }
        } else {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Accessibility Shield Active & Protecting",
                            color = EmeraldSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val intent = Intent(context, MindfulPauseActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    putExtra(MindfulPauseActivity.EXTRA_TARGET_APP, "YouTube (Test)")
                                    putExtra(MindfulPauseActivity.EXTRA_REASON, "Testing Mindful Shield")
                                    putExtra(MindfulPauseActivity.EXTRA_CAN_BYPASS, true)
                                }
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = ObsidianBase
                            ),
                            shape = RoundedCornerShape(9999.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Science,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = ObsidianBase
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Test Shield", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    }
                                } catch (_: Exception) {}
                            },
                            border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            shape = RoundedCornerShape(9999.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                        ) {
                            Text(text = "Open YouTube", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // Hero Card: Screen Time & Intentional Limit (Stitch Screen 1)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, HairlineBorder, RoundedCornerShape(20.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Screen Time",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(SurfaceContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Within Intentional Limit",
                            color = EmeraldSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "1h 42m",
                        color = TextPrimary,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                    Text(
                        text = "of 2h 30m target",
                        color = TextTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(EmeraldDark.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TrendingDown,
                        contentDescription = null,
                        tint = EmeraldTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "+${timeSavedMinutes}m saved vs typical day",
                        color = EmeraldTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // YouTube Shorts Fortress Quick Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, HairlineBorder, RoundedCornerShape(20.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceContainer)
                                .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayCircle,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "YouTube Shorts Shield",
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (isBlockShorts) "Auto-Pause Active" else "Shield Paused",
                                color = if (isBlockShorts) EmeraldSecondary else TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Switch(
                        checked = isBlockShorts,
                        onCheckedChange = { prefs.setBlockShortsEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianBase,
                            checkedTrackColor = EmeraldPrimary,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = SurfaceContainerHigh
                        )
                    )
                }

                if (isBlockShorts) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceContainer)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Allow First Short",
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isAllowFirstShorts)
                                    "Permits 1 initial short, blocks endless scroll"
                                else
                                    "Strict mode: blocks all shorts immediately",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isAllowFirstShorts,
                            onCheckedChange = { prefs.setAllowFirstShortsEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ObsidianBase,
                                checkedTrackColor = EmeraldSecondary,
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = SurfaceContainerHigh
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Mindful Interventions Today (Stitch Screen 1)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, HairlineBorder, RoundedCornerShape(20.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Mindful Interventions",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Today's friction triggers & pause gates",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(SurfaceContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$totalInterventions Total",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Mindful Closes
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceContainer)
                            .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Block,
                                    contentDescription = null,
                                    tint = EmeraldPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "$closePercent%",
                                    color = EmeraldSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$mindfulSaves",
                                color = TextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Mindful Closes",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Intentional Passes
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceContainer)
                            .border(1.dp, HairlineBorder, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LockOpen,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "$passPercent%",
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$intentionalPasses",
                                color = TextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Intentional Passes",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Strict Friction Shield Card (Stitch Master Toggle)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, HairlineBorder, RoundedCornerShape(20.dp))
                .clickable { onNavigateToRules() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceContainer)
                            .border(1.dp, HairlineBorder, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Strict Friction Shield",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Manage blocked channels & rulesets",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Switch(
                    checked = isShieldActive,
                    onCheckedChange = { prefs.setShieldActive(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ObsidianBase,
                        checkedTrackColor = EmeraldPrimary,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = SurfaceContainerHigh
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}
