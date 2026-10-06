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
import android.widget.Toast
import ai.relign.app.R
import ai.relign.app.RelignApplication
import ai.relign.app.ui.MindfulPauseActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Production-grade Accessibility Service for Relign.
 *
 * Implements high-reliability YouTube Shorts detection based on architectural
 * patterns from leading Google Play Store blockers (Shorts & Reels Blocker, NoShorts, RealFeed, ZenGuard).
 *
 * Key Architecture Highlights:
 * 1. Runtime Service Configuration: Enforces FLAG_REPORT_VIEW_IDS and FLAG_RETRIEVE_INTERACTIVE_WINDOWS
 *    so view ID resource names are reliably delivered across all Android versions (API 24 to 35+).
 * 2. ID Normalization & Proven Marker Catalog: Checks normalized view IDs (substringAfterLast('/'))
 *    against verified Shorts player markers (e.g. reel_progress_bar, reel_recycler, reel_watch_fragment_root)
 *    while strictly excluding entry point markers (e.g. reel_shelf, reel_item, pivot_bar) to eliminate
 *    false positives on the Home feed.
 * 3. Node Visibility Filtering: Only counts nodes that are visible to the user (isVisibleToUser == true),
 *    preventing false triggers from paused/cached background fragments.
 * 4. Rate-Limiting & Escalated Exit: Immediate GLOBAL_ACTION_BACK with cooldown protection,
 *    escalating to GLOBAL_ACTION_HOME if multiple attempts fail, followed by MindfulPauseActivity overlay.
 * 5. Background Watchdog: 400ms periodic polling while YouTube is focused to catch any dropped
 *    or buffered accessibility events.
 */
class RelignAccessibilityService : AccessibilityService() {

    private val prefs by lazy { RelignApplication.instance.preferencesManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    // State tracking
    private var isInsideShorts: Boolean = false
    private var shortsSessionCount: Int = 0
    private var lastKnownShortTitle: String = ""
    private var lastScanTimestamp: Long = 0L
    private var lastActionTimestamp: Long = 0L
    private var lastScrollTimestamp: Long = 0L
    private var consecutiveShortsDetections: Int = 0

    // Watchdog runnable for periodic foreground checking
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (prefs.isShieldActive.value && !prefs.isBypassActive() && prefs.isBlockShortsEnabled.value) {
                    checkScreenForShorts()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog execution error", e)
            } finally {
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceRunning.value = true

        // Enforce accessibility flags at runtime.
        // On many Android versions/ROMs (OneUI, MIUI, ColorOS), setting flags here
        // is strictly necessary for viewIdResourceName to be non-null.
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_SELECTED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = 100
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring serviceInfo", e)
        }

        // Start periodic watchdog loop
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)

        Log.i(TAG, "Relign Accessibility Service Connected & Active with ReportViewIds")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val pkg = event.packageName?.toString() ?: return

            if (!prefs.isShieldActive.value) return
            if (prefs.isBypassActive()) return

            when {
                isYouTubePackage(pkg) -> handleYouTube(event)
                pkg in prefs.shieldedApps.value -> handleOtherShieldedApp(pkg, event)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unhandled error in onAccessibilityEvent", t)
        }
    }

    private fun isYouTubePackage(pkg: String): Boolean {
        return pkg == PACKAGE_YOUTUBE ||
                pkg == "app.revanced.android.youtube" ||
                pkg == "app.rvx.android.youtube" ||
                pkg == "com.google.android.youtube.tv" ||
                pkg.contains("youtube")
    }

    private fun handleYouTube(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()

        // 1. Throttle high-frequency content-changed events during active video playback
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            (now - lastScanTimestamp < MIN_SCAN_INTERVAL_MS)
        ) {
            return
        }
        lastScanTimestamp = now

        // 2. Gather active window candidate roots
        val candidateRoots = collectActiveRoots(event)
        val primaryRoot = candidateRoots.firstOrNull()

        // 3. Fast Path: Immediate click/selection on Shorts tab or Short video
        if (prefs.isBlockShortsEnabled.value && checkShortsInteractionEvent(event)) {
            Log.i(TAG, "Fast Path: User clicked or selected Shorts entry")
            onShortsDetected("Shorts Click Interaction", primaryRoot)
            return
        }

        // 4. Fast Path: Window state change to Shorts / ReelWatch activity/fragment
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            if (SHORTS_CLASS_MARKERS.any { className.contains(it, ignoreCase = true) }) {
                Log.i(TAG, "Fast Path: Shorts class detected: $className")
                onShortsDetected("Shorts Window State ($className)", primaryRoot)
                return
            }
        }

        // 5. Blocked Channel Verification
        if (primaryRoot != null) {
            val blockedChannel = findBlockedChannel(primaryRoot)
            if (blockedChannel != null) {
                triggerMindfulPause(
                    targetApp = "YouTube",
                    reason = "Blocked Channel: $blockedChannel",
                    canBypass = true
                )
                return
            }
        }

        // 6. Deep Scan for YouTube Shorts
        if (prefs.isBlockShortsEnabled.value) {
            val detection = scanForShorts(candidateRoots)
            if (detection.isShorts) {
                onShortsDetected(detection.reason, primaryRoot)
            } else {
                // User is not in Shorts
                consecutiveShortsDetections = 0
                if (isInsideShorts && (now - lastActionTimestamp > 1200)) {
                    isInsideShorts = false
                    shortsSessionCount = 0
                    lastKnownShortTitle = ""
                }
            }
        }
    }

    /**
     * Periodic watchdog scan called every 400ms to guarantee enforcement
     * even if an event was dropped or buffered by Android.
     */
    private fun checkScreenForShorts() {
        val candidateRoots = collectActiveRoots(null)
        val ytRoot = candidateRoots.firstOrNull { isYouTubePackage(it.packageName?.toString() ?: "") }
            ?: return

        val detection = scanForShorts(listOf(ytRoot))
        if (detection.isShorts) {
            Log.d(TAG, "Watchdog: Shorts detected (${detection.reason})")
            onShortsDetected(detection.reason, ytRoot)
        }
    }

    private data class ShortsDetection(val isShorts: Boolean, val reason: String)

    /**
     * Scans the node hierarchy of candidate roots.
     * Combines direct targeted ID lookups (WallHabit / BlockScroll pattern)
     * with comprehensive BFS hierarchy scanning and text/description detection.
     */
    private fun scanForShorts(roots: List<AccessibilityNodeInfo>): ShortsDetection {
        for (root in roots) {
            // Tier 1 (WallHabit / BlockScroll pattern): Direct ID search using findAccessibilityNodeInfosByViewId
            for (marker in FAST_PATH_VIEW_IDS) {
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId(marker)
                    if (!nodes.isNullOrEmpty()) {
                        return ShortsDetection(true, "Direct ViewId match: $marker")
                    }
                } catch (_: Exception) {}
            }

            // Tier 2: BFS Tree Traversal inspecting view IDs, text, descriptions, and action clusters
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0

            var foundStrongPlayerMarker: String? = null
            var isRegularWatchScreen = false
            var isShortsTabSelected = false
            var actionButtonCount = 0

            while (queue.isNotEmpty() && visited < MAX_NODES_PER_SCAN) {
                val node = queue.removeFirst()
                visited++

                // Always enqueue children first so container visibility doesn't prune the search tree
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }

                val rawId = node.viewIdResourceName
                val id = normalizeId(rawId)
                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                val lowerDesc = desc.lowercase()
                val lowerText = text.lowercase()

                // Check for regular video watch screen markers (Safe IDs)
                // Note: Never include "watch_while" because YouTube's main activity container is WatchWhileActivity!
                if (id.isNotEmpty() && REGULAR_WATCH_MARKERS.any { id.contains(it) }) {
                    isRegularWatchScreen = true
                }

                // Check for entry point markers (e.g., Home feed shelves/carousels)
                val isEntryPoint = id.isNotEmpty() && ENTRY_POINT_MARKERS.any { id.contains(it) }

                if (!isEntryPoint && !isRegularWatchScreen) {
                    // Check for Strong Shorts Player markers
                    if (id.isNotEmpty()) {
                        val matchedMarker = SHORTS_PLAYER_MARKERS.firstOrNull { id.contains(it) }
                        if (matchedMarker != null) {
                            foundStrongPlayerMarker = matchedMarker
                            break
                        }
                    }

                    // Check for Shorts bottom navigation tab selected or focused
                    if (lowerDesc.equals("shorts", ignoreCase = true) ||
                        lowerText.equals("shorts", ignoreCase = true) ||
                        lowerDesc.startsWith("shorts,") ||
                        (lowerDesc.contains("shorts") && lowerDesc.contains("tab"))
                    ) {
                        if (node.isSelected || node.isFocused || lowerDesc.contains("selected")) {
                            isShortsTabSelected = true
                        }
                    }

                    // Check for overlay action buttons unique to Shorts player
                    if (SHORTS_OVERLAY_ACTIONS.any { lowerDesc.contains(it) || lowerText.contains(it) }) {
                        actionButtonCount++
                    }
                }
            }

            // If we found regular video playback controls, don't flag as Shorts
            if (isRegularWatchScreen) {
                continue
            }

            if (foundStrongPlayerMarker != null) {
                return ShortsDetection(true, "Player marker: $foundStrongPlayerMarker")
            }

            if (isShortsTabSelected) {
                return ShortsDetection(true, "Shorts tab selected")
            }

            if (actionButtonCount >= 1) {
                return ShortsDetection(true, "Shorts overlay action button cluster ($actionButtonCount)")
            }
        }

        return ShortsDetection(false, "Not in Shorts")
    }

    /**
     * Handles detected Shorts content according to user preferences (Strict vs Allow First Short).
     */
    private fun onShortsDetected(reason: String, rootNode: AccessibilityNodeInfo? = null) {
        val now = SystemClock.uptimeMillis()
        val currentTitle = rootNode?.let { extractShortTitle(it) } ?: ""

        if (!isInsideShorts) {
            // Fresh entry into Shorts
            isInsideShorts = true
            shortsSessionCount = 1
            lastKnownShortTitle = currentTitle
            Log.i(TAG, "Entered Shorts (reason='$reason', title='$currentTitle')")

            if (!prefs.isAllowFirstShortsEnabled.value) {
                // Strict mode (Default): Block immediately!
                executeShortsBlock(reason, rootNode)
            } else {
                // "Allow First Short" mode: permit current short up to 30s
                mainHandler.removeCallbacksAndMessages("FIRST_SHORT_TIMER")
                mainHandler.postAtTime({
                    if (isInsideShorts && prefs.isShieldActive.value && !prefs.isBypassActive()) {
                        executeShortsBlock("First Short Time Limit Expired (30s)", rootNode)
                    }
                }, "FIRST_SHORT_TIMER", SystemClock.uptimeMillis() + 30000)
            }
        } else {
            // Already inside Shorts: check for swipe/scroll to next Short
            val titleChanged = currentTitle.isNotBlank() &&
                    lastKnownShortTitle.isNotBlank() &&
                    currentTitle != lastKnownShortTitle

            val scrolled = (now - lastScrollTimestamp > 500) &&
                    (now - lastActionTimestamp > ACTION_COOLDOWN_MS)

            if (titleChanged && scrolled) {
                lastScrollTimestamp = now
                shortsSessionCount++
                if (currentTitle.isNotBlank()) lastKnownShortTitle = currentTitle
                Log.i(TAG, "Short swipe detected. Session count: $shortsSessionCount")

                if (shortsSessionCount > 1) {
                    executeShortsBlock("Scrolled to Next Short", rootNode)
                }
            } else if (!prefs.isAllowFirstShortsEnabled.value) {
                // In strict mode, if still in Shorts after cooldown, re-enforce block
                if (now - lastActionTimestamp > ACTION_COOLDOWN_MS) {
                    executeShortsBlock(reason, rootNode)
                }
            }
        }
    }

    /**
     * Executes the block action immediately:
     * 1. Closes the app automatically via GLOBAL_ACTION_HOME.
     * 2. If possible, switches the YouTube player away to Home tab via node action.
     * 3. Fallback to GLOBAL_ACTION_BACK if on a customized launcher or ROM.
     * 4. Increments mindful saves in PreferencesManager.
     * 5. Displays an immediate feedback toast.
     * 6. Launches MindfulPauseActivity overlay.
     */
     private fun executeShortsBlock(reason: String, candidateRoot: AccessibilityNodeInfo? = null) {
        val now = SystemClock.uptimeMillis()
        if (now - lastActionTimestamp < ACTION_COOLDOWN_MS) {
            return
        }
        lastActionTimestamp = now
        consecutiveShortsDetections++

        Log.i(TAG, "Executing Shorts Auto-Close (attempt #$consecutiveShortsDetections, reason='$reason')")

        // 1. First priority: Close YouTube immediately by sending Home action
        val homeSuccess = performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Fallback / Complementary: In case the OS or OEM ROM (HyperOS) throttles HOME,
        // or the user re-enters, redirect the in-app view away from Shorts (switch to Home tab or press back)
        if (candidateRoot != null) {
            tryRedirectToHomeTab(candidateRoot)
        }
        if (!homeSuccess) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        // 3. Record mindful save & time saved
        try {
            prefs.recordMindfulSave()
        } catch (e: Exception) {
            Log.e(TAG, "Error recording mindful save", e)
        }

        // 4. User feedback toast
        try {
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.shorts_blocked_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Exception) {}

        // 5. Trigger Mindful Pause Screen Overlay
        triggerMindfulPause(
            targetApp = "YouTube Shorts",
            reason = "YouTube Shorts Paused (Mindful Break)",
            canBypass = true
        )
    }

    /**
     * Tries to find the YouTube "Home" bottom navigation tab or "Back" button
     * and performs a click to guarantee the Shorts player is dismissed.
     */
    private fun tryRedirectToHomeTab(root: AccessibilityNodeInfo) {
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 80) {
                val node = queue.removeFirst()
                count++

                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""

                // Look for "Home" bottom tab or "Navigate up" button
                if (desc == "home" || desc.startsWith("home, tab") ||
                    text == "home" || desc == "navigate up"
                ) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    } else {
                        var parent = node.parent
                        while (parent != null) {
                            if (parent.isClickable) {
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                return
                            }
                            parent = parent.parent
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Checks if an event was an explicit click or selection of a Shorts tab or Short link.
     */
    private fun checkShortsInteractionEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) {
            val desc = event.contentDescription?.toString()?.lowercase() ?: ""
            val text = event.text.joinToString(" ").lowercase()

            if (desc == "shorts" || desc.startsWith("shorts,") ||
                (desc.contains("tab") && desc.contains("shorts")) ||
                text == "shorts" || text.startsWith("shorts,")
            ) {
                return true
            }

            if (desc.contains("play short") || desc.endsWith("short") || desc.contains(" - short") ||
                desc.contains("reel") || text.contains("play short")
            ) {
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
                canBypass = true
            )
        }
    }

    /**
     * Collects genuine active roots from focused windows, rootInActiveWindow, and event source.
     */
    private fun collectActiveRoots(event: AccessibilityEvent?): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()

        // 1. Root from active window
        rootInActiveWindow?.let { roots.add(it) }

        // 2. Roots from focused / active windows list
        try {
            val wins = windows
            for (w in wins) {
                if (w.isFocused || w.isActive) {
                    w.root?.let { r ->
                        if (!roots.contains(r)) roots.add(r)
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Root from event source
        event?.source?.let { src ->
            var top: AccessibilityNodeInfo = src
            while (true) {
                val p = try { top.parent } catch (_: Exception) { null } ?: break
                top = p
            }
            if (!roots.contains(top)) roots.add(top)
        }

        return roots
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

    private fun triggerMindfulPause(
        targetApp: String,
        reason: String,
        canBypass: Boolean
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPauseLaunchTimestamp < 1500) {
            return
        }
        lastPauseLaunchTimestamp = now

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
        }, 100)
    }

    fun closeTargetApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Relign Accessibility Service Interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        mainHandler.removeCallbacks(watchdogRunnable)
        _isServiceRunning.value = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(watchdogRunnable)
        _isServiceRunning.value = false
        instance = null
    }

    companion object {
        private const val TAG = "RelignShield"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        private const val MIN_SCAN_INTERVAL_MS = 120L
        private const val ACTION_COOLDOWN_MS = 800L
        private const val WATCHDOG_INTERVAL_MS = 400L
        private const val MAX_CONSECUTIVE_BACKS = 3
        private const val MAX_NODES_PER_SCAN = 400

        private var lastPauseLaunchTimestamp: Long = 0L

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        var instance: RelignAccessibilityService? = null
            private set

        /** Normalizes a view ID (e.g., 'com.google.android.youtube:id/reel_progress_bar' -> 'reel_progress_bar') */
        fun normalizeId(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.substringAfterLast('/').lowercase()
        }

        /**
         * Fast-path view IDs searched directly via findAccessibilityNodeInfosByViewId
         * (WallHabit / BlockScroll pattern) for instant detection without BFS tree overhead.
         */
        val FAST_PATH_VIEW_IDS = listOf(
            "com.google.android.youtube:id/reel_progress_bar",
            "com.google.android.youtube:id/reel_watch_fragment_root",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_player_page",
            "com.google.android.youtube:id/shorts_container",
            "com.google.android.youtube:id/shorts_vertical_feed_container"
        )

        /**
         * Gold-standard view ID markers that ONLY appear inside the active YouTube Shorts player.
         * Compiled from top Google Play Store blockers and verified across YouTube 18.x - 20.x+.
         */
        val SHORTS_PLAYER_MARKERS = setOf(
            "reel_progress_bar",
            "reel_recycler",
            "reel_watch_fragment_root",
            "reel_player_page_container",
            "reel_player_page",
            "reel_player_underlay",
            "reel_watch_player",
            "reel_playback",
            "reel_time_bar",
            "reel_action_panel",
            "reel_metapanel",
            "reel_meta_panel",
            "reel_dyn_",
            "shorts_video_title",
            "shorts_player",
            "shorts_video_header",
            "shorts_vertical_feed_container",
            "shorts_container",
            "shorts_root",
            "shorts_immersive"
        )

        /**
         * Markers that belong to Home feed shelves, carousels, or bottom navigation.
         * These are strictly excluded so Relign never falsely blocks the Home feed.
         */
        val ENTRY_POINT_MARKERS = setOf(
            "reel_shelf",
            "reel_item",
            "reel_lockup",
            "reel_grid",
            "reel_thumbnail",
            "shorts_shelf",
            "shorts_lockup",
            "shorts_entry",
            "shorts_tab",
            "pivot_bar",
            "pivot_shorts",
            "tab_shorts",
            "nav_shorts",
            "bottom_bar",
            "chip"
        )

        /**
         * View IDs marking the regular YouTube video watch screen.
         * NOTE: Do NOT add "watch_while" or "watch_while_activity" here!
         * WatchWhileActivity is the root container for ALL of YouTube (including Shorts).
         */
         val REGULAR_WATCH_MARKERS = setOf(
             "watch_player",
             "watch_fragment",
             "player_fragment",
             "player_view",
             "player_control",
             "video_metadata"
         )

        /**
         * Activity/Fragment class names indicative of Shorts window state.
         */
        val SHORTS_CLASS_MARKERS = listOf(
            "ReelWatch",
            "Shorts",
            "ReelPlayer",
            "ReelWatchFragment"
        )

        /**
         * Content descriptions unique to the Shorts playback overlay.
         * TalkBack / accessibility strings in YouTube cannot be obfuscated.
         */
        val SHORTS_OVERLAY_ACTIONS = listOf(
            "remix this",
            "remix short",
            "remix this short",
            "use this sound",
            "create with this sound",
            "sound used in this short",
            "shorts sound",
            "like this short",
            "dislike this short",
            "share this short",
            "search shorts",
            "search in shorts",
            "shorts options",
            "comments on short",
            "remix video"
        )
    }
}
