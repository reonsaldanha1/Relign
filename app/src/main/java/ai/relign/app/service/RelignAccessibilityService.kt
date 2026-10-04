package ai.relign.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.relign.app.RelignApplication
import ai.relign.app.ui.MindfulPauseActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

class RelignAccessibilityService : AccessibilityService() {

    private val prefs by lazy { RelignApplication.instance.preferencesManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isInsideShorts: Boolean = false
    private var shortsSessionCount: Int = 0
    private var lastKnownShortTitle: String = ""
    private var lastTriggerTimestamp: Long = 0L
    private var lastScrollTimestamp: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceRunning.value = true

        try {
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                notificationTimeout = 50
            }
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring serviceInfo", e)
        }

        Log.d(TAG, "Relign Accessibility Service Connected & Active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (!prefs.isShieldActive.value) return
        if (prefs.isBypassActive()) return

        when {
            pkg == PACKAGE_YOUTUBE -> handleYouTube(event)
            pkg in prefs.shieldedApps.value -> handleOtherShieldedApp(pkg, event)
        }
    }

    private fun handleYouTube(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: event.source ?: getFocusedWindowRoot() ?: return

        // 1. Check for Blocked Channels
        val blockedChannel = findBlockedChannel(rootNode)
        if (blockedChannel != null) {
            triggerMindfulPause(
                targetApp = "YouTube",
                reason = "Blocked Channel: $blockedChannel",
                canBypass = true
            )
            return
        }

        // 2. Check for YouTube Shorts if enabled
        if (prefs.isBlockShortsEnabled.value) {
            val inShortsNow = isShortsPresentOnScreen(rootNode)

            if (inShortsNow) {
                val currentTitle = extractShortTitle(rootNode)

                if (!isInsideShorts) {
                    // Just entered Shorts
                    isInsideShorts = true
                    shortsSessionCount = 1
                    lastKnownShortTitle = currentTitle
                    Log.d(TAG, "Entered Shorts. Title: $currentTitle, Count: 1")

                    if (!prefs.isAllowFirstShortsEnabled.value) {
                        // Strict mode: block immediately on 1st short!
                        triggerMindfulPause(
                            targetApp = "YouTube",
                            reason = "YouTube Shorts Blocked (First Short Restricted)",
                            canBypass = false
                        )
                        return
                    }
                } else {
                    // Already in Shorts: check for swipe to next short
                    val now = SystemClock.uptimeMillis()
                    val titleChanged = currentTitle.isNotBlank() &&
                            lastKnownShortTitle.isNotBlank() &&
                            currentTitle != lastKnownShortTitle &&
                            (now - lastScrollTimestamp > 800)

                    val viewScrolled = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                            (now - lastScrollTimestamp > 800)

                    if (titleChanged || viewScrolled) {
                        lastScrollTimestamp = now
                        shortsSessionCount++
                        if (currentTitle.isNotBlank()) {
                            lastKnownShortTitle = currentTitle
                        }
                        Log.d(TAG, "Swipe to next Short detected. Count: $shortsSessionCount")

                        if (shortsSessionCount > 1) {
                            triggerMindfulPause(
                                targetApp = "YouTube",
                                reason = "YouTube Shorts Limit Reached (1 Short Watched)",
                                canBypass = false
                            )
                            return
                        }
                    }
                }
            } else {
                // Not in Shorts
                if (isInsideShorts) {
                    isInsideShorts = false
                    shortsSessionCount = 0
                    lastKnownShortTitle = ""
                }
            }
        }
    }

    private fun handleOtherShieldedApp(packageName: String, event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val appName = when {
                packageName.contains("instagram") -> "Instagram"
                packageName.contains("tiktok") || packageName.contains("musically") -> "TikTok"
                packageName.contains("twitter") -> "X / Twitter"
                packageName.contains("reddit") -> "Reddit"
                else -> "Shielded App"
            }
            triggerMindfulPause(
                targetApp = appName,
                reason = "Intentional Pause Intervention",
                canBypass = true
            )
        }
    }

    private fun getFocusedWindowRoot(): AccessibilityNodeInfo? {
        try {
            val wins = windows
            for (w in wins) {
                if (w.isFocused || w.isActive) {
                    val root = w.root
                    if (root != null) return root
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun isShortsPresentOnScreen(rootNode: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var scanned = 0

        while (queue.isNotEmpty() && scanned < 250) {
            val node = queue.removeFirst()
            scanned++

            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            // 1. Reel progress bar / player (the gold-standard indicator for YouTube Shorts player)
            if ("reel_progress_bar" in id || "reel_player" in id || "reel_recycler" in id || "reel_watch_fragment" in id) {
                return true
            }

            // 2. View ID contains reel / shorts
            if ("reel" in id || "shorts_container" in id || "shorts_player" in id) {
                return true
            }

            // 3. Action buttons unique to Shorts viewer
            if ("remix this short" in desc ||
                "like this short" in desc ||
                "dislike this short" in desc ||
                "share this short" in desc ||
                "use this sound" in desc ||
                "create with this sound" in desc ||
                "remix" in desc ||
                "shorts sound" in desc
            ) {
                return true
            }

            // 4. Shorts tab selected in bottom bar or active
            if (desc.startsWith("shorts") && (node.isSelected || "selected" in desc)) {
                return true
            }
            if (text == "shorts" && (node.isSelected || "selected" in desc)) {
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }

        return false
    }

    private fun extractShortTitle(rootNode: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var scanned = 0

        while (queue.isNotEmpty() && scanned < 60) {
            val node = queue.removeFirst()
            scanned++

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            if (text.length in 6..120 && !text.equals("Shorts", ignoreCase = true) && !text.equals("Subscriptions", ignoreCase = true)) {
                return text
            }
            if (desc.length in 10..150 && !desc.contains("Shorts, tab", ignoreCase = true) && !desc.contains("Navigate up", ignoreCase = true)) {
                return desc
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return ""
    }

    private fun findBlockedChannel(rootNode: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var scanned = 0

        while (queue.isNotEmpty() && scanned < 120) {
            val node = queue.removeFirst()
            scanned++

            val text = node.text?.toString()
            if (!text.isNullOrBlank()) {
                val matched = prefs.isChannelBlocked(text)
                if (matched != null) return matched
            }

            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrBlank()) {
                val matched = prefs.isChannelBlocked(desc)
                if (matched != null) return matched
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
        return null
    }

    private fun triggerMindfulPause(targetApp: String, reason: String, canBypass: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerTimestamp < 2000) {
            return // Cooldown debounce
        }
        lastTriggerTimestamp = now

        Log.i(TAG, "Mindful Intervention: Performing BACK and launching Pause for $targetApp: $reason")

        // 1. Immediately press Back to kick YouTube out of the Shorts reel / video!
        performGlobalAction(GLOBAL_ACTION_BACK)

        // 2. Launch MindfulPauseActivity cleanly
        mainHandler.postDelayed({
            try {
                val intent = Intent(this, MindfulPauseActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MindfulPauseActivity.EXTRA_TARGET_APP, targetApp)
                    putExtra(MindfulPauseActivity.EXTRA_REASON, reason)
                    putExtra(MindfulPauseActivity.EXTRA_CAN_BYPASS, canBypass)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MindfulPauseActivity", e)
            }
        }, 150)
    }

    fun closeTargetApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Relign Accessibility Service Interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _isServiceRunning.value = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        instance = null
    }

    companion object {
        private const val TAG = "RelignShield"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        var instance: RelignAccessibilityService? = null
            private set
    }
}
