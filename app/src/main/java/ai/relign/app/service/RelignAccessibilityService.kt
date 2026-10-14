package ai.relign.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStatsManager
import android.content.Context
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
 * Relign Accessibility Service - v1.0.19
 *
 * ARCHITECTURE: Class-Name-First + UsageStats + Text Heuristics
 *
 * Modern YouTube (2025+) uses Litho/Bloks declarative UI which strips or obfuscates view IDs.
 * This service uses three layers of detection, in priority order:
 *
 * LAYER 1 – Window class name (TYPE_WINDOW_STATE_CHANGED event):
 *   YouTube reports class names at the OS level. When a Reel/Shorts fragment becomes the
 *   foreground, the className in the event contains "Reel", "Shorts", etc. This cannot
 *   be obfuscated since it is set by the Android framework, not the app UI layer.
 *   Also checks for Shorts URL patterns in event text.
 *
 * LAYER 2 – Text/Description heuristics (BFS node scan):
 *   Even with Litho UI, TalkBack accessibility labels (contentDescription, text) must be
 *   human-readable. We scan for Shorts-specific action labels ("remix this short", etc.)
 *   and check if the Shorts bottom-nav tab is selected.
 *
 * LAYER 3 – UsageStats foreground activity check:
 *   Uses UsageStatsManager to query the most recently used activity/class name. If the
 *   foreground class contains "Reel" or "Shorts", triggers the block.
 *
 * EXIT MECHANISM:
 *   - performGlobalAction(GLOBAL_ACTION_HOME) — primary
 *   - performGlobalAction(GLOBAL_ACTION_BACK) — repeated fallback (HyperOS / Xiaomi gesture nav)
 *   - tryClickHomeTab() — in-app Home tab click via accessibility
 *   - Mindful Pause overlay launched after close
 */
class RelignAccessibilityService : AccessibilityService() {

    private val prefs by lazy { RelignApplication.instance.preferencesManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    // State tracking
    private var isInsideShorts = false
    private var lastActionTimestamp = 0L
    private var lastShortsClassName = ""
    private var consecutiveShortsHits = 0

    // Watchdog: polls every 500ms using UsageStats + node scan
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (prefs.isShieldActive.value && !prefs.isBypassActive() && prefs.isBlockShortsEnabled.value) {
                    watchdogCheck()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog error", e)
            } finally {
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceRunning.value = true

        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SELECTED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.notificationTimeout = 50
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring serviceInfo", e)
        }

        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        Log.i(TAG, "Relign v1.0.19 Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val pkg = event.packageName?.toString() ?: return
            if (!prefs.isShieldActive.value) return
            if (prefs.isBypassActive()) return

            when {
                isYouTubePackage(pkg) -> handleYouTubeEvent(event)
                pkg in prefs.shieldedApps.value -> handleOtherShieldedApp(pkg, event)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onAccessibilityEvent", t)
        }
    }

    private fun isYouTubePackage(pkg: String) =
        pkg == PACKAGE_YOUTUBE ||
        pkg == "app.revanced.android.youtube" ||
        pkg == "app.rvx.android.youtube" ||
        pkg.contains("youtube")

    // -----------------------------------------------------------------------------------------
    // LAYER 1: Class-name detection on window state change events
    // -----------------------------------------------------------------------------------------
    private fun handleYouTubeEvent(event: AccessibilityEvent) {

        // ---- LAYER 1A: Window class name check (most reliable) ----
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            val pkg = event.packageName?.toString() ?: ""

            Log.d(TAG, "YT window: pkg=$pkg class=$className")

            if (prefs.isBlockShortsEnabled.value && isShortsClassName(className)) {
                Log.i(TAG, "LAYER1A: Shorts class detected → $className")
                lastShortsClassName = className
                triggerBlock("Class: $className")
                return
            }

            // If it's a known regular watch class, clear Shorts state
            if (isRegularWatchClassName(className)) {
                clearShortsState()
                return
            }

            // ---- LAYER 1B: URL/text in event for Shorts URL pattern ----
            val eventText = event.text.joinToString(" ")
            if (prefs.isBlockShortsEnabled.value && isShortsUrl(eventText)) {
                Log.i(TAG, "LAYER1B: Shorts URL in event text → $eventText")
                triggerBlock("URL: /shorts/")
                return
            }
        }

        // ---- LAYER 1C: Click on Shorts tab or Short entry (any event type) ----
        if (prefs.isBlockShortsEnabled.value && isShortsInteractionEvent(event)) {
            Log.i(TAG, "LAYER1C: Shorts interaction event")
            triggerBlock("Tap: Shorts tab/entry")
            return
        }

        // ---- LAYER 2: Node scan (runs on content-change events, throttled) ----
        if (prefs.isBlockShortsEnabled.value) {
            val now = SystemClock.uptimeMillis()
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                now - lastScanTimestamp < MIN_SCAN_INTERVAL_MS
            ) return
            lastScanTimestamp = now

            val root = safeRootNode() ?: return
            val result = scanNodeTree(root)
            if (result != null) {
                Log.i(TAG, "LAYER2: Node scan hit → $result")
                triggerBlock(result)
            } else {
                // Not in Shorts via node scan — only clear if enough time passed
                if (isInsideShorts && now - lastActionTimestamp > 2000) {
                    clearShortsState()
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // LAYER 3: Watchdog — UsageStats + periodic node scan
    // -----------------------------------------------------------------------------------------
    private fun watchdogCheck() {
        // 3A: UsageStats check
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5000, now)
                if (!stats.isNullOrEmpty()) {
                    val recent = stats.maxByOrNull { it.lastTimeUsed }
                    val pkg = recent?.packageName ?: ""
                    val cls = recent?.let {
                        try {
                            val f = it.javaClass.getDeclaredField("mLastForegroundActivityClassName")
                            f.isAccessible = true
                            f.get(it) as? String ?: ""
                        } catch (_: Exception) { "" }
                    } ?: ""

                    if (isYouTubePackage(pkg) && cls.isNotEmpty() && isShortsClassName(cls)) {
                        Log.i(TAG, "LAYER3A: UsageStats Shorts class → $cls")
                        triggerBlock("UsageStats: $cls")
                        return
                    }
                }
            }
        } catch (_: Exception) {}

        // 3B: Node scan watchdog
        val root = safeRootNode()
        if (root != null && isYouTubePackage(root.packageName?.toString() ?: "")) {
            val result = scanNodeTree(root)
            if (result != null) {
                Log.d(TAG, "LAYER3B: Watchdog node scan hit → $result")
                triggerBlock(result)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Node tree scanner (Layer 2 / 3B)
    // -----------------------------------------------------------------------------------------
    private fun scanNodeTree(root: AccessibilityNodeInfo): String? {
        // Fast path: direct view ID lookup for known Shorts IDs
        for (id in FAST_PATH_VIEW_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) return "ViewId: $id"
            } catch (_: Exception) {}
        }

        // BFS scan with text/description heuristics
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        var shortsActionCount = 0
        var isShortsTabSelected = false
        var hasRegularVideoSignal = false

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            val id = normalizeId(node.viewIdResourceName)
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""

            // Regular video watch screen signals (but NOT watch_while — that's the whole app)
            if (id.isNotEmpty() && REGULAR_VIDEO_IDS.any { id.contains(it) }) {
                hasRegularVideoSignal = true
            }

            // Shorts player view ID signals
            if (id.isNotEmpty() && SHORTS_PLAYER_IDS.any { id.contains(it) }) {
                return "NodeId: $id"
            }

            // Shorts bottom-nav tab selected
            if ((desc.startsWith("shorts") || text.startsWith("shorts")) &&
                (node.isSelected || node.isFocused || desc.contains("selected"))
            ) {
                isShortsTabSelected = true
            }

            // Shorts-unique TalkBack action strings
            if (SHORTS_ACTION_STRINGS.any { desc.contains(it) || text.contains(it) }) {
                shortsActionCount++
            }

            // Enqueue children
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }

        if (hasRegularVideoSignal) return null // Regular video, not Shorts

        if (isShortsTabSelected) return "ShortsTabSelected"
        if (shortsActionCount >= 1) return "ShortsActions($shortsActionCount)"

        return null
    }

    // -----------------------------------------------------------------------------------------
    // Block execution
    // -----------------------------------------------------------------------------------------
    private fun triggerBlock(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastActionTimestamp < ACTION_COOLDOWN_MS) return

        lastActionTimestamp = now
        isInsideShorts = true
        consecutiveShortsHits++

        Log.i(TAG, "BLOCKING Shorts #$consecutiveShortsHits — $reason")

        // 1. HOME action (primary)
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Back action 200ms later as fallback for HyperOS gesture nav
        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
        }, 200)

        // 3. Try clicking YouTube Home tab from within the app
        mainHandler.postDelayed({
            safeRootNode()?.let { tryClickHomeTab(it) }
        }, 350)

        // 4. Record + toast + overlay
        try { prefs.recordMindfulSave() } catch (_: Exception) {}

        mainHandler.post {
            try {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.shorts_blocked_toast),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {}
        }

        triggerMindfulPause("YouTube Shorts", "YouTube Shorts closed by Relign", true)
    }

    private fun clearShortsState() {
        isInsideShorts = false
        consecutiveShortsHits = 0
        lastShortsClassName = ""
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private fun safeRootNode(): AccessibilityNodeInfo? {
        return try { rootInActiveWindow } catch (_: Exception) { null }
    }

    private fun isShortsClassName(cls: String): Boolean {
        val c = cls.lowercase()
        return SHORTS_CLASS_KEYWORDS.any { c.contains(it) }
    }

    private fun isRegularWatchClassName(cls: String): Boolean {
        val c = cls.lowercase()
        return REGULAR_WATCH_CLASS_KEYWORDS.any { c.contains(it) }
    }

    private fun isShortsUrl(text: String): Boolean {
        return text.contains("/shorts/") || text.contains("youtube.com/shorts")
    }

    private fun isShortsInteractionEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) return false

        val desc = event.contentDescription?.toString()?.lowercase() ?: ""
        val text = event.text.joinToString(" ").lowercase()
        val combined = "$desc $text"

        // Direct Shorts tab tap
        if (combined.trim() == "shorts" ||
            combined.startsWith("shorts,") ||
            (combined.contains("shorts") && combined.contains("tab"))
        ) return true

        // Tap on a Short entry from the Home feed
        if (combined.contains("play short") ||
            combined.endsWith(" short") ||
            combined.contains(" - short") ||
            (combined.contains("short") && combined.contains("watch"))
        ) return true

        return false
    }

    private fun tryClickHomeTab(root: AccessibilityNodeInfo) {
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < 100) {
                val node = queue.removeFirst()
                count++
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                if (desc == "home" || desc.startsWith("home,") || text == "home") {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return
                    }
                    var p = node.parent
                    while (p != null) {
                        if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
                        p = p.parent
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            }
        } catch (_: Exception) {}
    }

    private fun handleOtherShieldedApp(pkg: String, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val appName = when {
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("tiktok") || pkg.contains("musically") -> "TikTok"
            pkg.contains("twitter") -> "X / Twitter"
            pkg.contains("reddit") -> "Reddit"
            else -> "Shielded App"
        }
        triggerMindfulPause(appName, "Intentional Pause Intervention", true)
    }

    private fun triggerMindfulPause(targetApp: String, reason: String, canBypass: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPauseLaunchTimestamp < 1500) return
        lastPauseLaunchTimestamp = now

        mainHandler.postDelayed({
            try {
                startActivity(Intent(applicationContext, MindfulPauseActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MindfulPauseActivity.EXTRA_TARGET_APP, targetApp)
                    putExtra(MindfulPauseActivity.EXTRA_REASON, reason)
                    putExtra(MindfulPauseActivity.EXTRA_CAN_BYPASS, canBypass)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch MindfulPauseActivity", e)
            }
        }, 120)
    }

    fun closeTargetApp() { performGlobalAction(GLOBAL_ACTION_HOME) }

    override fun onInterrupt() { Log.w(TAG, "Service interrupted") }

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

    // -----------------------------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------------------------
    companion object {
        private const val TAG = "RelignShield"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        private const val MIN_SCAN_INTERVAL_MS = 150L
        private const val ACTION_COOLDOWN_MS = 1000L
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val MAX_NODES = 350

        private var lastScanTimestamp = 0L
        private var lastPauseLaunchTimestamp = 0L

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        var instance: RelignAccessibilityService? = null
            private set

        fun normalizeId(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.substringAfterLast('/').lowercase()
        }

        /**
         * Class name keywords that identify the YouTube Shorts player.
         * These are reported by Android's window manager and cannot be obfuscated by YouTube's UI layer.
         *
         * Sources: Android accessibility event className, UsageStats activity class names,
         * verified by adb shell dumpsys activity while in Shorts.
         */
        val SHORTS_CLASS_KEYWORDS = listOf(
            "reelwatch",
            "reelplayer",
            "shortsplayer",
            "shorts_player",
            "shortsactivity",
            "reelwatchfragment",
            "shortsplayerfragment",
            "shortsvideoverticalfeedactivity"
        )

        /**
         * Class name keywords that identify the regular YouTube video watch screen.
         * If we see these, the user is watching a normal video — never block.
         */
        val REGULAR_WATCH_CLASS_KEYWORDS = listOf(
            "watchendpoint",
            "regularvideo",
            "singlecolumnwatchnext"
        )

        /**
         * Direct findAccessibilityNodeInfosByViewId IDs — works when YouTube doesn't obfuscate IDs.
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
         * Normalized view ID substrings unique to the Shorts player node tree.
         */
        val SHORTS_PLAYER_IDS = setOf(
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
            "shorts_video_title",
            "shorts_player",
            "shorts_video_header",
            "shorts_vertical_feed_container",
            "shorts_container",
            "shorts_root"
        )

        /**
         * View IDs that indicate a regular YouTube video watch screen.
         * NOTE: Do NOT include "watch_while" — that is the root activity for the entire YouTube app,
         * including Shorts. Including it caused ALL Shorts detections to be bypassed.
         */
        val REGULAR_VIDEO_IDS = setOf(
            "watch_player",
            "watch_fragment",
            "player_fragment",
            "player_view",
            "player_control_view",
            "video_metadata"
        )

        /**
         * TalkBack contentDescription / text strings unique to the Shorts playback overlay.
         * These strings are human-readable accessibility labels — YouTube cannot obfuscate them
         * without breaking TalkBack / screen reader support.
         */
        val SHORTS_ACTION_STRINGS = listOf(
            "remix this",
            "remix short",
            "remix this short",
            "use this sound",
            "create with this sound",
            "sound used in this short",
            "like this short",
            "dislike this short",
            "share this short",
            "search in shorts",
            "search shorts",
            "shorts options",
            "comments on short",
            "remix video",
            "add a comment on this short"
        )
    }
}
