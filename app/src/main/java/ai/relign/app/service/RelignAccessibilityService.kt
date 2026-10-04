package ai.relign.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.relign.app.RelignApplication
import ai.relign.app.ui.MindfulPauseActivity

class RelignAccessibilityService : AccessibilityService() {

    private val prefs by lazy { RelignApplication.instance.preferencesManager }

    private var currentPackage: String = ""
    private var isInsideShorts: Boolean = false
    private var shortsSessionCount: Int = 0
    private var lastTriggerTimestamp: Long = 0L
    private var lastScrollTimestamp: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Relign Mindful Shield Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        currentPackage = pkg

        if (!prefs.isShieldActive.value) return
        if (prefs.isBypassActive()) return

        when (pkg) {
            PACKAGE_YOUTUBE -> handleYouTube(event)
            in prefs.shieldedApps.value -> handleOtherShieldedApp(pkg, event)
        }
    }

    private fun handleYouTube(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return

        // 1. Check for Blocked Channels first
        val blockedChannel = findBlockedChannelInNode(rootNode)
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
            val inShortsNow = detectIfInShorts(rootNode)

            if (inShortsNow) {
                if (!isInsideShorts) {
                    // Newly entered Shorts feed
                    isInsideShorts = true
                    shortsSessionCount = 1

                    if (!prefs.isAllowFirstShortsEnabled.value) {
                        // Immediately block on 1st short!
                        triggerMindfulPause(
                            targetApp = "YouTube",
                            reason = "YouTube Shorts Blocked (First Short Restricted)",
                            canBypass = false
                        )
                        return
                    } else {
                        // Allow 1st short, log notice
                        Log.d(TAG, "Allowed first short session. Count: $shortsSessionCount")
                    }
                } else {
                    // User is already inside shorts. Did they scroll or attempt second short?
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastScrollTimestamp > 1200) {
                            lastScrollTimestamp = now
                            shortsSessionCount++
                            Log.d(TAG, "Shorts scroll detected. Count: $shortsSessionCount")

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
                }
            } else {
                // Not in shorts anymore (e.g. Home, Subscriptions, Library)
                if (isInsideShorts) {
                    isInsideShorts = false
                    shortsSessionCount = 0
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

    private fun detectIfInShorts(rootNode: AccessibilityNodeInfo): Boolean {
        // Direct checks on hierarchy for YouTube Shorts identifiers
        return searchShortsNodes(rootNode, 0)
    }

    private fun searchShortsNodes(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 12) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        // Check if bottom navigation "Shorts" tab is selected
        if (desc.contains("shorts") && node.isSelected) {
            return true
        }

        // Check view IDs typical for YouTube Shorts player / reel
        if (viewId.contains("reel_recycler") ||
            viewId.contains("reel_player_page_view") ||
            viewId.contains("reel_view_pager") ||
            viewId.contains("shorts_container") ||
            viewId.contains("reel_watch_fragment") ||
            viewId.contains("shorts_player_fragment")
        ) {
            return true
        }

        // Check content descriptions specifically indicating the active Shorts reel
        if (desc.contains("shorts player") || desc.contains("reel player") || text == "shorts") {
            if (node.isClickable || node.isScrollable || node.isSelected) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            if (searchShortsNodes(node.getChild(i), depth + 1)) {
                return true
            }
        }

        return false
    }

    private fun findBlockedChannelInNode(rootNode: AccessibilityNodeInfo): String? {
        return searchChannelText(rootNode, 0)
    }

    private fun searchChannelText(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 14) return null

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
            val matchedChild = searchChannelText(node.getChild(i), depth + 1)
            if (matchedChild != null) return matchedChild
        }

        return null
    }

    private fun triggerMindfulPause(targetApp: String, reason: String, canBypass: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerTimestamp < 2500) {
            return // Debounce to avoid overlapping overlay triggers
        }
        lastTriggerTimestamp = now

        Log.i(TAG, "Triggering Mindful Pause for $targetApp: $reason")

        val intent = Intent(this, MindfulPauseActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MindfulPauseActivity.EXTRA_TARGET_APP, targetApp)
            putExtra(MindfulPauseActivity.EXTRA_REASON, reason)
            putExtra(MindfulPauseActivity.EXTRA_CAN_BYPASS, canBypass)
        }
        startActivity(intent)
    }

    fun closeTargetApp() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBackInTargetApp() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Relign Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        private const val TAG = "RelignShield"
        const val PACKAGE_YOUTUBE = "com.google.android.youtube"

        var instance: RelignAccessibilityService? = null
            private set
    }
}
