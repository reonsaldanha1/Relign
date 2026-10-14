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
 * Relign v1.0.20 - Definitive Shorts + Channel Blocker
 *
 * DETECTION STRATEGY (in priority order):
 *
 * 1. EVENT TEXT / URL DETECTION
 *    When YouTube navigates to a Short, the TYPE_WINDOW_STATE_CHANGED event fires and
 *    event.text often contains the URL fragment or page title. We check for "/shorts/"
 *    in the combined event text. This is the most reliable on modern YouTube.
 *
 * 2. CLASS NAME DETECTION
 *    Android reports the fragment/activity class name in the accessibility event.
 *    We check for "Reel", "Shorts", etc. — these can't be obfuscated by YouTube's UI.
 *
 * 3. BOTTOM NAV "SHORTS" TAB SELECTED
 *    When the user taps the Shorts tab, TYPE_VIEW_SELECTED / TYPE_VIEW_CLICKED fires
 *    with contentDescription = "Shorts" and isSelected=true.
 *
 * 4. WATCHDOG NODE SCAN (every 600ms)
 *    Full BFS scan of the accessibility tree: view IDs (when available) + TalkBack
 *    action strings unique to the Shorts player overlay.
 *
 * CHANNEL BLOCKING:
 *    Deep node scan for channel name text inside the active YouTube window.
 *    Scans on every WINDOW_STATE_CHANGED and WINDOW_CONTENT_CHANGED event.
 *
 * EXIT MECHANISM:
 *    performGlobalAction(GLOBAL_ACTION_HOME) immediately.
 *    + GLOBAL_ACTION_BACK 300ms later as fallback for HyperOS.
 *    + MindfulPauseActivity overlay.
 */
class RelignAccessibilityService : AccessibilityService() {

    private val prefs by lazy { RelignApplication.instance.preferencesManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Block cooldown state
    private var lastBlockTimestamp = 0L
    private var isInShortsSession = false

    // Watchdog
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                if (prefs.isShieldActive.value && !prefs.isBypassActive()) {
                    runWatchdog()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog error: ${e.message}")
            } finally {
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
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
            info.notificationTimeout = 0
            serviceInfo = info
        } catch (e: Exception) {
            Log.e(TAG, "Config error: ${e.message}")
        }

        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        Log.i(TAG, "=== Relign v1.0.20 Service Connected ===")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val pkg = event.packageName?.toString() ?: return
            if (!prefs.isShieldActive.value) return
            if (prefs.isBypassActive()) return

            if (isYouTube(pkg)) {
                handleYouTubeEvent(event)
            } else if (pkg in prefs.shieldedApps.value) {
                handleShieldedAppEvent(pkg, event)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Event error: ${t.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YOUTUBE EVENT HANDLER
    // ─────────────────────────────────────────────────────────────────────────
    private fun handleYouTubeEvent(event: AccessibilityEvent) {

        val eventType = event.eventType
        val className = event.className?.toString() ?: ""
        val eventTextRaw = event.text.joinToString(" ")
        val eventDesc = event.contentDescription?.toString() ?: ""
        val combined = "$eventTextRaw $eventDesc $className".lowercase()

        // ── DEBUG: Log every window-state-change so we can see what YouTube emits ──
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "YT_WIN: class='$className' text='$eventTextRaw' desc='$eventDesc'")
        }

        // ══════════════════════════════════════════════════════════
        // SIGNAL 1: URL "/shorts/" pattern in event text
        // YouTube emits the URL fragment in event text for many versions
        // ══════════════════════════════════════════════════════════
        if (prefs.isBlockShortsEnabled.value &&
            (combined.contains("/shorts/") || combined.contains("youtube.com/shorts"))
        ) {
            Log.i(TAG, "SHORTS_URL detected in event text")
            blockShorts("Shorts URL in event")
            return
        }

        // ══════════════════════════════════════════════════════════
        // SIGNAL 2: Class name detection (TYPE_WINDOW_STATE_CHANGED)
        // ══════════════════════════════════════════════════════════
        if (prefs.isBlockShortsEnabled.value &&
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val cls = className.lowercase()
            if (SHORTS_CLASS_SIGNALS.any { cls.contains(it) }) {
                Log.i(TAG, "SHORTS_CLASS detected: $className")
                blockShorts("Shorts class: $className")
                return
            }
            // If it's a known regular video watch screen, clear Shorts state
            if (REGULAR_VIDEO_CLASSES.any { cls.contains(it) }) {
                isInShortsSession = false
                Log.d(TAG, "Regular video class detected, cleared Shorts state")
            }
        }

        // ══════════════════════════════════════════════════════════
        // SIGNAL 3: Bottom-nav Shorts tab click/selection
        // ══════════════════════════════════════════════════════════
        if (prefs.isBlockShortsEnabled.value &&
            isShortsTabEvent(event)
        ) {
            Log.i(TAG, "SHORTS_TAB click/selection event detected")
            blockShorts("Shorts tab tapped")
            return
        }

        // ══════════════════════════════════════════════════════════
        // CHANNEL BLOCKING (runs on every event, throttled)
        // ══════════════════════════════════════════════════════════
        if (prefs.blockedChannels.value.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            if (now - lastChannelScanTimestamp > CHANNEL_SCAN_INTERVAL_MS) {
                lastChannelScanTimestamp = now
                val root = safeRoot()
                if (root != null) {
                    val blocked = findBlockedChannel(root)
                    if (blocked != null) {
                        Log.i(TAG, "BLOCKED_CHANNEL detected: $blocked")
                        blockChannel(blocked)
                        return
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════
        // SIGNAL 4: Node scan on content changes (throttled)
        // ══════════════════════════════════════════════════════════
        if (prefs.isBlockShortsEnabled.value &&
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val now = SystemClock.uptimeMillis()
            if (now - lastNodeScanTimestamp < NODE_SCAN_INTERVAL_MS) return
            lastNodeScanTimestamp = now

            val root = safeRoot() ?: return
            val hit = nodeScanForShorts(root)
            if (hit != null) {
                Log.i(TAG, "SHORTS_NODE detected: $hit")
                blockShorts("Node: $hit")
            } else {
                // Not in Shorts — clear session state if enough time has passed
                if (isInShortsSession && now - lastBlockTimestamp > 2500) {
                    isInShortsSession = false
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WATCHDOG (600ms interval)
    // ─────────────────────────────────────────────────────────────────────────
    private fun runWatchdog() {
        val root = safeRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!isYouTube(pkg)) return

        // Node scan
        if (prefs.isBlockShortsEnabled.value) {
            val hit = nodeScanForShorts(root)
            if (hit != null) {
                Log.d(TAG, "WATCHDOG_SHORTS: $hit")
                blockShorts("Watchdog: $hit")
                return
            }
        }

        // Channel scan
        if (prefs.blockedChannels.value.isNotEmpty()) {
            val blocked = findBlockedChannel(root)
            if (blocked != null) {
                Log.d(TAG, "WATCHDOG_CHANNEL: $blocked")
                blockChannel(blocked)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NODE SCAN FOR SHORTS
    // ─────────────────────────────────────────────────────────────────────────
    private fun nodeScanForShorts(root: AccessibilityNodeInfo): String? {
        // Fast path: findAccessibilityNodeInfosByViewId
        for (id in FAST_PATH_IDS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    return "viewId=$id"
                }
            } catch (_: Exception) {}
        }

        // findAccessibilityNodeInfosByText for Shorts-specific text
        for (searchText in TEXT_SEARCH_TERMS) {
            try {
                val nodes = root.findAccessibilityNodeInfosByText(searchText)
                if (!nodes.isNullOrEmpty()) {
                    // Make sure we're not just on the home feed showing a Shorts shelf
                    val node = nodes[0]
                    val id = normalizeId(node.viewIdResourceName)
                    if (ENTRY_POINT_IDS.none { id.contains(it) }) {
                        return "text=$searchText"
                    }
                }
            } catch (_: Exception) {}
        }

        // BFS scan
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var shortsActionHits = 0
        var hasRegularVideoSignal = false
        var isShortsTabSelected = false

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            val id = normalizeId(node.viewIdResourceName)
            val desc = (node.contentDescription?.toString() ?: "").lowercase().trim()
            val text = (node.text?.toString() ?: "").lowercase().trim()

            // Strong Shorts player IDs → immediate signal
            if (id.isNotEmpty() && SHORTS_PLAYER_IDS.any { id.contains(it) }) {
                return "id=$id"
            }

            // Regular video indicators (not watch_while — that's the whole app container!)
            if (id.isNotEmpty() && REGULAR_VIDEO_IDS.any { id.contains(it) }) {
                hasRegularVideoSignal = true
            }

            // Shorts bottom tab selected
            if ((desc.startsWith("shorts") || text == "shorts") &&
                (node.isSelected || node.isFocused || desc.contains("selected"))
            ) {
                isShortsTabSelected = true
            }

            // Shorts-unique TalkBack labels
            if (SHORTS_ACTION_LABELS.any { desc.contains(it) || text.contains(it) }) {
                shortsActionHits++
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        if (hasRegularVideoSignal) return null
        if (isShortsTabSelected) return "shorts_tab_selected"
        if (shortsActionHits >= 1) return "action_labels($shortsActionHits)"
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHANNEL BLOCKER
    // ─────────────────────────────────────────────────────────────────────────
    private fun findBlockedChannel(root: AccessibilityNodeInfo): String? {
        val blockedList = prefs.blockedChannels.value
        if (blockedList.isEmpty()) return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < CHANNEL_MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""

            for (raw in listOf(text, desc)) {
                if (raw.isBlank()) continue
                val lower = raw.lowercase()
                for (channel in blockedList) {
                    val cleanChannel = channel.removePrefix("@").trim().lowercase()
                    if (cleanChannel.isNotEmpty() && lower.contains(cleanChannel)) {
                        return channel
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHORTS TAB CLICK/SELECTION CHECK
    // ─────────────────────────────────────────────────────────────────────────
    private fun isShortsTabEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
        ) return false

        val desc = (event.contentDescription?.toString() ?: "").lowercase()
        val text = event.text.joinToString(" ").lowercase()
        val combined = "$desc $text".trim()

        // Exact Shorts tab match
        if (combined == "shorts" || combined.startsWith("shorts,") ||
            (combined.contains("shorts") && combined.contains("tab"))) {
            return true
        }

        // Short entry from feed
        if (combined.contains("play short") || combined.endsWith(" short") ||
            combined.contains("- shorts") || combined.contains(", shorts")) {
            return true
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLOCK ACTIONS
    // ─────────────────────────────────────────────────────────────────────────
    private fun blockShorts(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockTimestamp < BLOCK_COOLDOWN_MS) return

        lastBlockTimestamp = now
        isInShortsSession = true
        Log.i(TAG, "⛔ BLOCKING SHORTS — $reason")

        // 1. Go Home immediately
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Back as fallback 300ms later (HyperOS gesture nav)
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300)

        // 3. Record + feedback
        try { prefs.recordMindfulSave() } catch (_: Exception) {}

        mainHandler.post {
            try {
                Toast.makeText(applicationContext, getString(R.string.shorts_blocked_toast), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }

        triggerMindfulPause("YouTube Shorts", "YouTube Shorts closed by Relign", true)
    }

    private fun blockChannel(channelName: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockTimestamp < BLOCK_COOLDOWN_MS) return

        lastBlockTimestamp = now
        Log.i(TAG, "⛔ BLOCKING CHANNEL — $channelName")

        performGlobalAction(GLOBAL_ACTION_HOME)
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300)

        try { prefs.recordMindfulSave() } catch (_: Exception) {}

        triggerMindfulPause("YouTube", "Blocked channel: $channelName", true)
    }

    private fun handleShieldedAppEvent(pkg: String, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val appName = when {
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("tiktok") || pkg.contains("musically") -> "TikTok"
            pkg.contains("twitter") -> "X / Twitter"
            pkg.contains("reddit") -> "Reddit"
            else -> "Shielded App"
        }
        triggerMindfulPause(appName, "Intentional Pause", true)
    }

    private fun triggerMindfulPause(targetApp: String, reason: String, canBypass: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPauseLaunchTimestamp < 1500L) return
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
                Log.e(TAG, "MindfulPause launch failed: ${e.message}")
            }
        }, 150)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────
    private fun safeRoot(): AccessibilityNodeInfo? =
        try { rootInActiveWindow } catch (_: Exception) { null }

    private fun isYouTube(pkg: String) =
        pkg == PACKAGE_YOUTUBE ||
        pkg == "app.revanced.android.youtube" ||
        pkg == "app.rvx.android.youtube" ||
        pkg.contains("youtube")

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

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTANTS
    // ─────────────────────────────────────────────────────────────────────────
    companion object {
        private const val TAG = "RelignShield"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        private const val BLOCK_COOLDOWN_MS = 1200L
        private const val WATCHDOG_INTERVAL_MS = 600L
        private const val NODE_SCAN_INTERVAL_MS = 200L
        private const val CHANNEL_SCAN_INTERVAL_MS = 500L
        private const val MAX_NODES = 300
        private const val CHANNEL_MAX_NODES = 150

        private var lastNodeScanTimestamp = 0L
        private var lastChannelScanTimestamp = 0L
        private var lastPauseLaunchTimestamp = 0L

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        var instance: RelignAccessibilityService? = null
            private set

        fun normalizeId(raw: String?): String =
            if (raw.isNullOrBlank()) "" else raw.substringAfterLast('/').lowercase()

        /**
         * Class name substrings that indicate the Shorts player fragment is active.
         * Verified via `adb shell dumpsys activity` while Shorts is open.
         */
        val SHORTS_CLASS_SIGNALS = listOf(
            "reelwatch",
            "reelplayer",
            "reelwatchfragment",
            "shortsplayer",
            "shortsvideoverticalfeed",
            "shortsactivity",
            "shortsplayerfragment",
            "reelactivity"
        )

        /**
         * Class names that confirm the user is watching a regular YouTube video (not Shorts).
         */
        val REGULAR_VIDEO_CLASSES = listOf(
            "watchendpoint",
            "singlecolumnwatchnext"
        )

        /**
         * Direct findAccessibilityNodeInfosByViewId IDs — works when YouTube preserves IDs.
         */
        val FAST_PATH_IDS = listOf(
            "com.google.android.youtube:id/reel_progress_bar",
            "com.google.android.youtube:id/reel_watch_fragment_root",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_player_page",
            "com.google.android.youtube:id/shorts_container",
            "com.google.android.youtube:id/shorts_vertical_feed_container"
        )

        /**
         * Text terms to search for via findAccessibilityNodeInfosByText.
         * These appear in the Shorts player UI as visible labels.
         */
        val TEXT_SEARCH_TERMS = listOf(
            "Shorts",
            "Remix",
            "Use this sound"
        )

        /**
         * Normalized view ID substrings unique to the Shorts player node tree.
         * IMPORTANT: Do NOT include "watch_while" — that's YouTube's top-level activity
         * container used for ALL screens including regular videos.
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
         * View IDs used in entry-point contexts (Home feed Shorts shelf) — NOT the player.
         * We exclude these so the Home feed never triggers a block.
         */
        val ENTRY_POINT_IDS = setOf(
            "reel_shelf", "reel_item", "reel_lockup", "reel_thumbnail",
            "shorts_shelf", "shorts_lockup", "shorts_tab", "pivot_bar", "chip"
        )

        /**
         * View IDs for a regular YouTube video watch screen.
         * Presence of these means the user is watching a normal video, not Shorts.
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
         * TalkBack contentDescription/text labels exclusive to the Shorts playback overlay.
         * YouTube cannot remove these without breaking accessibility / TalkBack compliance.
         */
        val SHORTS_ACTION_LABELS = listOf(
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
            "add a comment on this short",
            "remix video"
        )
    }
}
