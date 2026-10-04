package ai.relign.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.Calendar

data class UsageReport(
    val totalScreenTimeMinutes: Long,
    val youtubeTimeMinutes: Long,
    val formattedTotalTime: String,
    val formattedYouTubeTime: String
)

object UsageStatsUtil {

    fun hasUsagePermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallback)
        }
    }

    /**
     * Calculates the real screen time for today starting from midnight (00:00).
     */
    fun getTodayScreenTime(context: Context): UsageReport {
        if (!hasUsagePermission(context)) {
            return UsageReport(
                totalScreenTimeMinutes = 0,
                youtubeTimeMinutes = 0,
                formattedTotalTime = "0m",
                formattedYouTubeTime = "0m"
            )
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return UsageReport(0, 0, "0m", "0m")

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        var totalForegroundMillis = 0L
        var youtubeMillis = 0L

        try {
            // UsageEvents gives precise foreground session calculation
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            val appStartMap = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue

                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        appStartMap[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        val start = appStartMap.remove(pkg)
                        if (start != null && event.timeStamp > start) {
                            val duration = event.timeStamp - start
                            totalForegroundMillis += duration
                            if (pkg == "com.google.android.youtube") {
                                youtubeMillis += duration
                            }
                        }
                    }
                }
            }

            // Close currently active foreground apps
            val now = System.currentTimeMillis()
            for ((pkg, start) in appStartMap) {
                if (now > start && (now - start) < 4 * 3600 * 1000) { // cap at 4 hrs sanity check
                    val duration = now - start
                    totalForegroundMillis += duration
                    if (pkg == "com.google.android.youtube") {
                        youtubeMillis += duration
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback to queryUsageStats if queryEvents was empty or restricted
        if (totalForegroundMillis == 0L) {
            try {
                val statsList = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime
                )
                if (!statsList.isNullOrEmpty()) {
                    for (stat in statsList) {
                        val time = stat.totalTimeInForeground
                        if (time > 0) {
                            totalForegroundMillis += time
                            if (stat.packageName == "com.google.android.youtube") {
                                youtubeMillis += time
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val totalMinutes = totalForegroundMillis / (1000 * 60)
        val ytMinutes = youtubeMillis / (1000 * 60)

        return UsageReport(
            totalScreenTimeMinutes = totalMinutes,
            youtubeTimeMinutes = ytMinutes,
            formattedTotalTime = formatMinutes(totalMinutes),
            formattedYouTubeTime = formatMinutes(ytMinutes)
        )
    }

    private fun formatMinutes(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m"
        }
    }
}
