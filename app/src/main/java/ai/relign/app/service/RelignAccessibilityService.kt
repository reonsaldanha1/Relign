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
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                notificationTimeout = 20
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

    private var shortsEntryTimestamp: Long = 0L

    private fun handleYouTube(event: AccessibilityEvent) {
        // Collect nodes from active window, event source, or any interactive windows
        val rootNode = rootInActiveWindow ?: event.source ?: getFocusedWindowRoot()

        // Fast path 1: Check if the event itself is an obvious Shorts indicator
        val eventClass = event.className?.toString()?.lowercase() ?: ""
        val eventTexts = event.text?.joinToString(" ")?.lowercase() ?: ""
        val eventDesc = event.contentDescription?.toString()?.lowercase() ?: ""

        val isEventIndicatingShorts = eventClass.contains("reel") ||
                eventClass.contains("shorts") ||
                eventTexts.contains("shorts") ||
                eventDesc.contains("shorts") ||
                eventDesc.contains("remix this") ||
                eventDesc.contains("use this sound")

        // 1. Check for Blocked Channels if we have a node tree
        if (rootNode != null) {
            val blockedChannel = findBlockedChannel(rootNode)
            if (blockedChannel != null) {
                triggerMindfulPause(
                    targetApp = "YouTube",
                    reason = "Blocked Channel: $blockedChannel",
                    canBypass = true
                )
                return
            }
        }

        // 2. Check for YouTube Shorts if enabled
        if (prefs.isBlockShortsEnabled.value) {
            val inShortsNow = isEventIndicatingShorts || (rootNode != null && isShortsPresentOnScreen(rootNode))

            if (inShortsNow) {
                val currentTitle = if (rootNode != null) extractShortTitle(rootNode) else ""
                val now = SystemClock.uptimeMillis()

                if (!isInsideShorts) {
                    // Just entered Shorts
                    isInsideShorts = true
                    shortsSessionCount = 1
                    shortsEntryTimestamp = now
                    lastKnownShortTitle = currentTitle
                    Log.i(TAG, "Entered YouTube Shorts viewer (title='$currentTitle')")

                    if (!prefs.isAllowFirstShortsEnabled.value) {
                        // Strict mode (Default): Block immediately on 1st short!
                        triggerMindfulPause(
                            targetApp = "YouTube",
                            reason = "YouTube Shorts Blocked (Mindful Pause)",
                            canBypass = false
                        )
                        return
                    } else {
                        // "Allow First Short" is enabled:
                        // Allow 25 seconds of watching before proactive mindful intervention!
                        mainHandler.postDelayed({
                            if (isInsideShorts && prefs.isShieldActive.value && !prefs.isBypassActive()) {
                                triggerMindfulPause(
                                    targetApp = "YouTube",
                                    reason = "First Short Time Limit Reached (25s)",
                                    canBypass = false
                                )
                            }
                        }, 25000)
                    }
                } else {
                    // Already inside Shorts:
                    val timeInShorts = now - shortsEntryTimestamp
                    val titleChanged = currentTitle.isNotBlank() &&
                            lastKnownShortTitle.isNotBlank() &&
                            currentTitle != lastKnownShortTitle &&
                            (now - lastScrollTimestamp > 600)

                    val isScrollEvent = (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                            event.eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_END) &&
                            (now - lastScrollTimestamp > 600)

                    // Also if user has scrolled or spent > 25 seconds in shorts with "Allow First Short"
                    if (titleChanged || isScrollEvent) {
                        lastScrollTimestamp = now
                        shortsSessionCount++
                        if (currentTitle.isNotBlank()) {
                            lastKnownShortTitle = currentTitle
                        }
                        Log.i(TAG, "Short swipe detected. Session count: $shortsSessionCount")

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
                    shortsEntryTimestamp = 0L
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
        // Also inspect any other interactive window if present
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)

        try {
            windows.forEach { window ->
                val r = window.root
                if (r != null && r != rootNode) {
                    queue.add(r)
                }
            }
        } catch (_: Exception) {}

        var scanned = 0

        while (queue.isNotEmpty() && scanned < 400) {
            val node = queue.removeFirst()
            scanned++

            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val cls = node.className?.toString()?.lowercase() ?: ""

            // 1. Reel / Shorts layout container or fragment ID
            if ("reel" in id ||
                "shorts_container" in id ||
                "shorts_player" in id ||
                "reel_player" in id ||
                "reel_recycler" in id ||
                "reel_progress_bar" in id ||
                "reel_watch_fragment" in id ||
                "reel_video_tv" in id ||
                "modern_reel_holder" in id ||
                "shorts_shelf" in id
            ) {
                return true
            }

            // 2. Class names unique to Shorts
            if ("reelplayer" in cls || "reelrecycler" in cls || "shortsview" in cls) {
                return true
            }

            // 3. Action buttons unique to the Shorts playback overlay
            if ("remix this" in desc ||
                "like this short" in desc ||
                "dislike this short" in desc ||
                "share this short" in desc ||
                "use this sound" in desc ||
                "create with this sound" in desc ||
                "shorts sound" in desc ||
                "remix with" in desc ||
                desc == "remix" ||
                desc == "shorts" && node.isSelected
            ) {
                return true
            }

            // 4. Content descriptions mentioning "short" or "shorts" in player controls
            if (("like" in desc || "dislike" in desc || "share" in desc || "comment" in desc) &&
                ("short" in desc || "video along with" in desc)
            ) {
                return true
            }

            // 5. Shorts bottom tab is active
            if ((desc.startsWith("shorts") || text == "shorts") &&
                (node.isSelected || node.isFocused || "selected" in desc || "tab 2 of" in desc)
            ) {
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

        while (queue.isNotEmpty() && scanned < 100) {
            val node = queue.removeFirst()
            scanned++

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            if (text.length in 5..140 &&
                !text.equals("Shorts", ignoreCase = true) &&
                !text.equals("Subscriptions", ignoreCase = true) &&
                !text.equals("Home", ignoreCase = true) &&
                !text.equals("Library", ignoreCase = true)
            ) {
                return text
            }
            if (desc.length in 8..150 &&
                !desc.contains("Shorts, tab", ignoreCase = true) &&
                !desc.contains("Navigate up", ignoreCase = true) &&
                !desc.contains("Search", ignoreCase = true)
            ) {
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
