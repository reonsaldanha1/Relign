package ai.relign.app.service

import android.accessibilityservice.AccessibilityService
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
    private var lastShortsSeenTimestamp: Long = 0L

    private val asyncCheckRunnable = Runnable {
        checkCurrentScreenForShorts()
    }

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
            isYouTubePackage(pkg) -> handleYouTube(event)
            pkg in prefs.shieldedApps.value -> handleOtherShieldedApp(pkg, event)
        }
    }

    private fun isYouTubePackage(pkg: String): Boolean {
        return pkg == PACKAGE_YOUTUBE ||
                pkg == "app.revanced.android.youtube" ||
                pkg == "app.rvx.android.youtube" ||
                pkg.contains("youtube")
    }

    private fun handleYouTube(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        val pkg = event.packageName?.toString() ?: PACKAGE_YOUTUBE

        // 1. Gather all genuine roots & candidates
        val (primaryRoot, candidateRoots) = collectCandidateRoots(event, pkg)

        // 2. Check for Blocked Channels
        if (primaryRoot != null) {
            val blockedChannel = findBlockedChannel(primaryRoot)
            if (blockedChannel != null) {
                triggerMindfulPause(
                    targetApp = "YouTube",
                    reason = "Blocked Channel: $blockedChannel",
                    canBypass = true,
                    rootNode = primaryRoot
                )
                return
            }
        }

        // 3. Check for YouTube Shorts if enabled
        if (prefs.isBlockShortsEnabled.value) {
            val isExplicitShortsClick = checkShortsInteractionEvent(event)
            val isShortsInTree = isShortsVisibleAnywhere(candidateRoots, event)
            val inShortsNow = isExplicitShortsClick || isShortsInTree

            if (inShortsNow) {
                lastShortsSeenTimestamp = now
                val currentTitle = primaryRoot?.let { extractShortTitle(it) } ?: ""

                if (!isInsideShorts) {
                    // Newly entered Shorts
                    isInsideShorts = true
                    shortsSessionCount = 1
                    shortsEntryTimestamp = now
                    lastKnownShortTitle = currentTitle
                    Log.i(TAG, "Entered YouTube Shorts (title='$currentTitle')")

                    if (!prefs.isAllowFirstShortsEnabled.value) {
                        // Strict mode (Default): Block immediately on 1st short!
                        triggerMindfulPause(
                            targetApp = "YouTube Shorts",
                            reason = "YouTube Shorts Paused (Mindful Break)",
                            canBypass = true,
                            rootNode = primaryRoot
                        )
                        return
                    } else {
                        // "Allow First Short" is enabled:
                        // Permit watching 1st short up to 30s before mindful intervention
                        mainHandler.removeCallbacksAndMessages("FIRST_SHORT_TIMER")
                        mainHandler.postAtTime({
                            if (isInsideShorts && prefs.isShieldActive.value && !prefs.isBypassActive()) {
                                triggerMindfulPause(
                                    targetApp = "YouTube Shorts",
                                    reason = "First Short Time Limit Reached (30s)",
                                    canBypass = true,
                                    rootNode = primaryRoot
                                )
                            }
                        }, "FIRST_SHORT_TIMER", SystemClock.uptimeMillis() + 30000)
                    }
                } else {
                    // Already in Shorts: detect swipe / scroll to next Short
                    val titleChanged = currentTitle.isNotBlank() &&
                            lastKnownShortTitle.isNotBlank() &&
                            currentTitle != lastKnownShortTitle &&
                            (now - lastScrollTimestamp > 400)

                    val isScrollEvent = (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                            event.eventType == AccessibilityEvent.TYPE_GESTURE_DETECTION_END) &&
                            (now - lastScrollTimestamp > 400)

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
                                canBypass = true,
                                rootNode = primaryRoot
                            )
                            return
                        }
                    }
                }
            } else {
                // Not in Shorts in this event: apply hysteresis so transient frames don't reset session
                if (isInsideShorts && (now - lastShortsSeenTimestamp > 1500)) {
                    isInsideShorts = false
                    shortsSessionCount = 0
                    lastKnownShortTitle = ""
                    shortsEntryTimestamp = 0L
                    lastShortsSeenTimestamp = 0L
                }
            }

            // Schedule asynchronous re-check for dynamic/delayed UI rendering
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                mainHandler.removeCallbacks(asyncCheckRunnable)
                mainHandler.postDelayed(asyncCheckRunnable, 250)
            }
        }
    }

    private fun checkCurrentScreenForShorts() {
        if (!prefs.isShieldActive.value || prefs.isBypassActive() || !prefs.isBlockShortsEnabled.value) return

        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { candidateRoots.add(it) }
        getFocusedWindowRoot()?.let { candidateRoots.add(it) }
        try {
            windows.forEach { w -> w.root?.let { candidateRoots.add(it) } }
        } catch (_: Exception) {}

        val primaryRoot = candidateRoots.firstOrNull { it.packageName?.toString()?.let(::isYouTubePackage) == true }
            ?: candidateRoots.firstOrNull() ?: return

        if (primaryRoot.packageName?.toString()?.let(::isYouTubePackage) != true) return

        val inShorts = isShortsVisibleAnywhere(candidateRoots, null)

        if (inShorts) {
            val now = SystemClock.uptimeMillis()
            lastShortsSeenTimestamp = now
            if (!isInsideShorts) {
                isInsideShorts = true
                shortsSessionCount = 1
                shortsEntryTimestamp = now
                if (!prefs.isAllowFirstShortsEnabled.value) {
                    triggerMindfulPause(
                        targetApp = "YouTube Shorts",
                        reason = "YouTube Shorts Paused (Mindful Break)",
                        canBypass = true,
                        rootNode = primaryRoot
                    )
                }
            }
        }
    }

    private fun checkShortsInteractionEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED
        ) {
            val desc = event.contentDescription?.toString()?.lowercase() ?: ""
            val text = event.text.joinToString(" ").lowercase()

            // 1. Shorts bottom tab clicked/selected
            if (desc == "shorts" || desc.startsWith("shorts,") ||
                (desc.contains("tab 2") && desc.contains("shorts")) || text == "shorts"
            ) {
                return true
            }

            // 2. Short feed video / card clicked
            if (desc.contains("play short") || desc.endsWith("short") || desc.contains(" - short")) {
                return true
            }
        }
        return false
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

    private fun collectCandidateRoots(
        event: AccessibilityEvent,
        expectedPkg: String
    ): Pair<AccessibilityNodeInfo?, List<AccessibilityNodeInfo>> {
        val candidateRoots = mutableListOf<AccessibilityNodeInfo>()

        // 1. Root from active window (highest fidelity)
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == expectedPkg) {
                candidateRoots.add(root)
            }
        }

        // 2. Root from event walking upwards to top parent
        rootFromEvent(event, expectedPkg)?.let { root ->
            if (!candidateRoots.contains(root)) {
                candidateRoots.add(root)
            }
        }

        // 3. Focused window root
        getFocusedWindowRoot()?.let { root ->
            if (root.packageName?.toString() == expectedPkg && !candidateRoots.contains(root)) {
                candidateRoots.add(root)
            }
        }

        // 4. Windows list roots
        try {
            windows.forEach { w ->
                w.root?.let { r ->
                    if (r.packageName?.toString() == expectedPkg && !candidateRoots.contains(r)) {
                        candidateRoots.add(r)
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: event.source
        event.source?.let { src ->
            if (!candidateRoots.contains(src)) {
                candidateRoots.add(src)
            }
        }

        val primaryRoot = candidateRoots.firstOrNull()
        return Pair(primaryRoot, candidateRoots)
    }

    /**
     * Combined precision Shorts detector checking event metadata and all candidate trees.
     */
    private fun isShortsVisibleAnywhere(roots: List<AccessibilityNodeInfo>, event: AccessibilityEvent? = null): Boolean {
        // Fast path 1: Event metadata (if event provided)
        if (event != null) {
            val eventClass = event.className?.toString()?.lowercase() ?: ""
            val eventTexts = event.text.joinToString(" ").lowercase()
            val eventDesc = event.contentDescription?.toString()?.lowercase() ?: ""

            if (eventClass.contains("reel") || eventClass.contains("shorts")) {
                return true
            }

            if (eventDesc.contains("search shorts") || eventDesc.contains("search in shorts") ||
                eventDesc.contains("remix this") || eventDesc.contains("remix short") ||
                eventDesc.contains("use this sound") || eventDesc.contains("create with this sound") ||
                eventDesc.contains("shorts sound") || eventDesc.contains("like this short") ||
                eventDesc.contains("dislike this short") || eventDesc.contains("share this short") ||
                eventDesc.contains("play short") || eventDesc.contains("pause short") ||
                eventTexts.contains("remix this") || eventTexts.contains("remix short")
            ) {
                return true
            }
        }

        // Fast path 2: Direct ID matching across roots
        val shortsViewIds = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_watch_fragment_root",
            "com.google.android.youtube:id/reel_player_page",
            "com.google.android.youtube:id/reel_player_page_adapter",
            "com.google.android.youtube:id/reel_video_tv",
            "com.google.android.youtube:id/reel_progress_bar",
            "com.google.android.youtube:id/reel_view_pager",
            "com.google.android.youtube:id/reel_holder",
            "com.google.android.youtube:id/reel_player",
            "com.google.android.youtube:id/reel_overlay",
            "com.google.android.youtube:id/modern_reel_holder",
            "com.google.android.youtube:id/shorts_container",
            "com.google.android.youtube:id/shorts_player",
            "com.google.android.youtube:id/shorts_player_view",
            "com.google.android.youtube:id/shorts_view_pager",
            "com.google.android.youtube:id/shorts_video_layout",
            "com.google.android.youtube:id/shorts_root"
        )

        for (root in roots) {
            try {
                for (viewId in shortsViewIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                    if (!nodes.isNullOrEmpty()) {
                        return true
                    }
                }
            } catch (_: Exception) {}
        }

        // BFS path: Deep inspection across candidate trees
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var scanned = 0

            var foundDislikeButton = false
            var foundCommentsButton = false
            var foundRemixOrSound = false

            while (queue.isNotEmpty() && scanned < 500) {
                val node = queue.removeFirst()
                scanned++

                val id = node.viewIdResourceName?.lowercase() ?: ""
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                val cls = node.className?.toString()?.lowercase() ?: ""

                // 1. Reel / Shorts layout container or fragment ID (excluding shelves/carousels)
                if (id.isNotEmpty() && !id.contains("shelf") && !id.contains("carousel") && !id.contains("rich_grid")) {
                    if ("reel" in id || "shorts_container" in id || "shorts_player" in id ||
                        "shorts_view_pager" in id || "shorts_root" in id || "modern_reel" in id
                    ) {
                        return true
                    }
                }

                // 2. Class names unique to Shorts
                if ("reelplayer" in cls || "reelrecycler" in cls || "shortsview" in cls || "reelviewpager" in cls) {
                    return true
                }

                // 3. Shorts bottom tab is active / selected
                val isShortsTab = desc.contains("shorts") || text == "shorts"
                if (isShortsTab) {
                    val isTabSelected = node.isSelected || node.isFocused ||
                            desc.contains("selected") || desc.contains("active") ||
                            node.collectionItemInfo?.isSelected == true
                    if (isTabSelected) {
                        return true
                    }
                }

                // 4. Action buttons unique to the Shorts playback overlay
                if (desc.contains("search shorts") || desc.contains("search in shorts") ||
                    desc.contains("remix this") || desc.contains("remix short") ||
                    (desc.contains("remix") && (desc.contains("video") || desc.contains("audio") || desc.contains("sound") || desc.contains("button"))) ||
                    desc.contains("use this sound") || desc.contains("create with this sound") ||
                    desc.contains("sound details") || desc.contains("shorts sound") ||
                    desc.contains("original sound") || desc.contains("original audio") ||
                    desc.contains("like this short") || desc.contains("dislike this short") ||
                    desc.contains("share this short") || desc.contains("play short") ||
                    desc.contains("pause short")
                ) {
                    return true
                }

                // 5. Cluster heuristic: Vertical action stack of short-form video player
                if (desc.contains("dislike")) foundDislikeButton = true
                if (desc.contains("comment")) foundCommentsButton = true
                if (desc.contains("remix") || desc.contains("sound") || desc.contains("audio")) foundRemixOrSound = true

                if (foundDislikeButton && foundCommentsButton && foundRemixOrSound) {
                    return true
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
            }
        }

        return false
    }

    private fun extractShortTitle(rootNode: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(rootNode)
        var scanned = 0

        while (queue.isNotEmpty() && scanned < 150) {
            val node = queue.removeFirst()
            scanned++

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            if (text.length in 5..140 &&
                !text.equals("Shorts", ignoreCase = true) &&
                !text.equals("Subscriptions", ignoreCase = true) &&
                !text.equals("Home", ignoreCase = true) &&
                !text.equals("Library", ignoreCase = true) &&
                !text.equals("You", ignoreCase = true)
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

        while (queue.isNotEmpty() && scanned < 150) {
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
     * 1. Exits Shorts back to Home feed (using bottom pivot_bar click or Global Back)
     * 2. Opens MindfulPauseActivity overlay
     */
    private fun triggerMindfulPause(
        targetApp: String,
        reason: String,
        canBypass: Boolean,
        rootNode: AccessibilityNodeInfo?
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerTimestamp < 1200) {
            return // Cooldown debounce
        }
        lastTriggerTimestamp = now

        Log.i(TAG, "Mindful Intervention: Exiting Shorts and showing Pause for $targetApp: $reason")

        // Step 1: Attempt to navigate back to the Home tab via the bottom pivot_bar
        var navigatedHome = false
        val rootsToTry = mutableListOf<AccessibilityNodeInfo>()
        rootNode?.let { rootsToTry.add(it) }
        rootInActiveWindow?.let { rootsToTry.add(it) }
        getFocusedWindowRoot()?.let { rootsToTry.add(it) }

        for (root in rootsToTry) {
            if (tryClickHomeTab(root)) {
                navigatedHome = true
                break
            }
        }

        // Step 2: Fallback to GLOBAL_ACTION_BACK to exit fullscreen Shorts player
        if (!navigatedHome) {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK)
            } catch (_: Exception) {}
        }

        // Step 3: Launch MindfulPauseActivity safely
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

    private fun tryClickHomeTab(root: AccessibilityNodeInfo): Boolean {
        try {
            // Check pivot_bar
            val pivotBars = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/pivot_bar")
            if (!pivotBars.isNullOrEmpty()) {
                val pivotBar = pivotBars[0]
                if (pivotBar.childCount > 0) {
                    val firstTab = pivotBar.getChild(0)
                    if (firstTab != null) {
                        if (firstTab.isClickable && firstTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                        for (i in 0 until firstTab.childCount) {
                            val sub = firstTab.getChild(i)
                            if (sub != null && sub.isClickable && sub.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                return true
                            }
                        }
                    }
                }
            }

            // Search by text "Home"
            val homeNodes = root.findAccessibilityNodeInfosByText("Home")
            if (!homeNodes.isNullOrEmpty()) {
                for (hNode in homeNodes) {
                    var curr: AccessibilityNodeInfo? = hNode
                    var depth = 0
                    while (curr != null && depth < 4) {
                        val desc = curr.contentDescription?.toString()?.lowercase() ?: ""
                        val text = curr.text?.toString()?.lowercase() ?: ""
                        if ((desc.contains("home") || text == "home") && curr.isClickable) {
                            if (curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                return true
                            }
                        }
                        curr = curr.parent
                        depth++
                    }
                }
            }
        } catch (_: Exception) {}
        return false
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
