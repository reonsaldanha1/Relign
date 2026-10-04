package ai.relign.app.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import ai.relign.app.service.RelignAccessibilityService

object AccessibilityUtil {

    fun isServiceEnabled(context: Context): Boolean {
        // 1. Live instance check from running service
        if (RelignAccessibilityService.isServiceRunning.value || RelignAccessibilityService.instance != null) {
            return true
        }

        val targetPackage = context.packageName // "ai.relign.app"
        val targetServiceClass = "RelignAccessibilityService"

        // 2. AccessibilityManager enabled services list
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null && am.isEnabled) {
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                for (service in enabledServices) {
                    val id = service.id ?: ""
                    if (id.contains(targetPackage, ignoreCase = true) &&
                        id.contains(targetServiceClass, ignoreCase = true)
                    ) {
                        return true
                    }
                    val info = service.resolveInfo?.serviceInfo
                    if (info != null &&
                        info.packageName.equals(targetPackage, ignoreCase = true) &&
                        info.name.contains(targetServiceClass, ignoreCase = true)
                    ) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Settings.Secure check
        try {
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabledServicesSetting.isNullOrBlank()) {
                if (enabledServicesSetting.contains(targetPackage, ignoreCase = true) &&
                    enabledServicesSetting.contains(targetServiceClass, ignoreCase = true)
                ) {
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }
}
