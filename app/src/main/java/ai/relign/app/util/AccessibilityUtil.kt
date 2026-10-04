package ai.relign.app.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import ai.relign.app.service.RelignAccessibilityService

object AccessibilityUtil {

    fun isServiceEnabled(context: Context): Boolean {
        // 1. Live instance check
        if (RelignAccessibilityService.isServiceRunning.value || RelignAccessibilityService.instance != null) {
            return true
        }

        // 2. AccessibilityManager enabled services list
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledServices = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (enabledServices != null) {
                for (service in enabledServices) {
                    val info = service.resolveInfo?.serviceInfo ?: continue
                    if (info.packageName == context.packageName &&
                        info.name == RelignAccessibilityService::class.java.name
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
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            val myService = ComponentName(context, RelignAccessibilityService::class.java).flattenToString()
            val myServiceShort = ComponentName(context, RelignAccessibilityService::class.java).flattenToShortString()

            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(myService, ignoreCase = true) ||
                    componentName.equals(myServiceShort, ignoreCase = true)
                ) {
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }
}
