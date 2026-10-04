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
    private var shortsEntryTimestamp: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceRunning.value = true
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
        val rootNode = rootFromEvent(event, PACKAGE_YOUTUBE) ?: rootInActiveWindow ?: getFocusedWindowRoot() ?: return

        // 1. Check for Blocked Channels
        val blockedChannel = findBlockedChannel(rootNode)
        if (blockedChannel != null) {
            triggerMindfulPause(
                targetApp = "YouTube",
                reason = "Blocked Channel: $blockedChannel",
                canBypass = true,
                rootNode = rootNode
            )
            return
        }

        // 2. Check for YouTube Shorts if enabled
        if (prefs.isBlockShortsEnabled.value) {
            val inShortsNow = isShortsVisibleOnScreen(rootNode, event)

            if (inShortsNow) {
                val currentTitle = extractShortTitle(rootNode)
                val now = SystemClock.uptimeMillis()

                if (!isInsideShorts) {
                    // Entered Shorts
                    isInsideShorts = true
                    shortsSessionCount = 1
                    shortsEntryTimestamp = now
                    lastKnownShortTitle = currentTitle
                    Log.i(TAG, "Entered YouTube Shorts viewer (title='$currentTitle')")

                    if (!prefs.isAllowFirstShortsEnabled.value) {
                        // Strict mode (Default): Block immediately on 1st short!
                        triggerMindfulPause(
                            targetApp = "YouTube Shorts",
                            reason = "YouTube Shorts Paused (Mindful Break)",
                            canBypass = false,
                            rootNode = rootNode
                        )
                        return
                    } else {
                        // "Allow First Short" is enabled:
                        // Permit watching 1st short up to 30s before mindful intervention
                        mainHandler.postDelayed({
                            if (isInsideShorts && prefs.isShieldActive.value && !prefs.isBypassActive()) {
                                triggerMindfulPause(
                                    targetApp = "YouTube Shorts",
                                    reason = "First Short Time Limit Reached (30s)",
                                    canBypass = false,
                                    rootNode = rootNode
                                )
                            }
                        }, 30000)
                    }
                } else {
                    // Already in Shorts: detect swipe / scroll to next Short
                    val titleChanged = currentTitle.isNotBlank() &&
                            lastKnownShortTitle.isNotBlank() &&
                            currentTitle != lastKnownShortTitle &&
                            (now - lastScrollTimestamp > 600)

                    val isScrollEvent = (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                            event.eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_END) &&
                            (now - lastScrollTimestamp > 600)

                    if (titleChanged || isScrollEvent) {
                        lastScrollTimestamp = now
                        shortsSessionCount++
                        if (currentTitle.isNotBlank()) {
                            lastKnownShortTitle = currentTitle
                        }
                        Log.i(TAG, "Short swipe detected. Session count: $shortsSessionCount")

                        if (shortsSessionCount > 1) {
                            triggerMindfulPause(
                                targetApp = "YouTube Shorts",
                                reason = "YouTube Shorts Limit Reached (1 Short Watched)",
                                canBypass = false,
                                rootNode = rootNode
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
                canBypass = true,
                rootNode = null
            )
        }
    }

    /**
     * Walks up from event.source to the top parent matching the expected package.
     * Prevents inspecting unwanted foreground overlays.
     */
    private fun rootFromEvent(event: AccessibilityEvent, expectedPkg: String): AccessibilityNodeInfo? {
        var node = event.source ?: return null
        if (node.packageName?.toString() != expectedPkg) {
            return null
        }
        while (true) {
            val parent = try {
                node.parent
            } catch (_: Exception) {
                null
            } ?: return node

            if (parent.packageName?.toString() != expectedPkg) {
                return node
            }
            node = parent
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

    /**
     * Combined precision Shorts detector incorporating both Blockfy ID targeting
     * and modern YouTube node attributes.
     */
    private fun isShortsVisibleOnScreen(rootNode: AccessibilityNodeInfo, event: AccessibilityEvent): Boolean {
        // Method A (Blockfy proven check): direct search by ID
        try {
            val blockfyIds = listOf(
                "com.google.android.youtube:id/reel_watch_fragment_root",
                "com.google.android.youtube:id/reel_recycler",
                "com.google.android.youtube:id/reel_player_page",
                "com.google.android.youtube:id/reel_video_tv"
            )
            for (viewId in blockfyIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
                if (!nodes.isNullOrEmpty()) {
                    val isVisible = nodes.any { it.isVisibleToUser }
                    nodes.forEach { it.recycle() }
                    if (isVisible) return true
                }
            }
        } catch (_: Exception) {}

        // Method B: Event metadata
        val eventClass = event.className?.toString()?.lowercase() ?: ""
        val eventTexts = event.text?.joinToString(" ")?.lowercase() ?: ""
        val eventDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        if (eventClass.contains("reel") || eventClass.contains("shorts") ||
            eventTexts.contains("remix this") || eventDesc.contains("remix this short") ||
            eventDesc.contains("use this sound")
        ) {
            return true
        }

        // Method C: Full BFS queue traversal across nodes
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var scanned = 0

        while (queue.isNotEmpty() && scanned < 250) {
            val node = queue.removeFirst()
            scanned++

            val id = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val cls = node.className?.toString()?.lowercase() ?: ""

            // 1. Reel / Shorts layout container or fragment ID
            if ("reel_watch_fragment" in id ||
                "reel_recycler" in id ||
                "reel_player" in id ||
                "reel_progress_bar" in id ||
                "shorts_container" in id ||
                "shorts_player" in id ||
                "modern_reel_holder" in id
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
                desc == "remix"
            ) {
                return true
            }

            // 4. Shorts bottom tab is active
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

        while (queue.isNotEmpty() && scanned < 80) {
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

        while (queue.isNotEmpty() && scanned < 100) {
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

    /**
     * Mindful intervention:
     * 1. Exits Shorts back to Home feed (using Blockfy pivot_bar click or Global Back)
     * 2. Opens MindfulPauseActivity overlay
     */
    private fun triggerMindfulPause(
        targetApp: String,
        reason: String,
        canBypass: Boolean,
        rootNode: AccessibilityNodeInfo?
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerTimestamp < 1500) {
            return // Cooldown debounce
        }
        lastTriggerTimestamp = now

        Log.i(TAG, "Mindful Intervention: Exiting Shorts and showing Pause for $targetApp: $reason")

        // Step 1: Exit Shorts feed gracefully (Blockfy mechanism)
        var navigatedHome = false
        if (rootNode != null) {
            try {
                val pivotBar = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/pivot_bar")?.firstOrNull()
                val homeTab = pivotBar?.getChild(0)?.getChild(0)
                if (homeTab != null) {
                    homeTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    navigatedHome = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate via pivot_bar", e)
            }
        }

        // Fallback: If pivot bar wasn't clicked, perform global back to leave the Shorts player
        if (!navigatedHome) {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (_: Exception) {}
        }

        // Step 2: Launch MindfulPauseActivity safely
        mainHandler.postDelayed({
            try {
                val intent = Intent(applicationContext, MindfulPauseActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MindfulPauseActivity.EXTRA_TARGET_APP, targetApp)
                    putExtra(MindfulPauseActivity.EXTRA_REASON, reason)
                    putExtra(MindfulPauseActivity.EXTRA_CAN_BYPASS, canBypass)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MindfulPauseActivity", e)
            }
        }, 120)
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
