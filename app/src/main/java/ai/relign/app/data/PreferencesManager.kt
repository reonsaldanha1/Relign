package ai.relign.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isShieldActive = MutableStateFlow(prefs.getBoolean(KEY_SHIELD_ACTIVE, true))
    val isShieldActive: StateFlow<Boolean> = _isShieldActive.asStateFlow()

    private val _isBlockShortsEnabled = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_SHORTS, true))
    val isBlockShortsEnabled: StateFlow<Boolean> = _isBlockShortsEnabled.asStateFlow()

    private val _isAllowFirstShortsEnabled = MutableStateFlow(prefs.getBoolean(KEY_ALLOW_FIRST_SHORTS, true))
    val isAllowFirstShortsEnabled: StateFlow<Boolean> = _isAllowFirstShortsEnabled.asStateFlow()

    private val _blockedChannels = MutableStateFlow(
        prefs.getStringSet(KEY_BLOCKED_CHANNELS, defaultBlockedChannels)?.toSet() ?: defaultBlockedChannels
    )
    val blockedChannels: StateFlow<Set<String>> = _blockedChannels.asStateFlow()

    private val _shieldedApps = MutableStateFlow(
        prefs.getStringSet(KEY_SHIELDED_APPS, defaultShieldedApps)?.toSet() ?: defaultShieldedApps
    )
    val shieldedApps: StateFlow<Set<String>> = _shieldedApps.asStateFlow()

    private val _mindfulSavesCount = MutableStateFlow(prefs.getInt(KEY_MINDFUL_SAVES, 11))
    val mindfulSavesCount: StateFlow<Int> = _mindfulSavesCount.asStateFlow()

    private val _intentionalPassesCount = MutableStateFlow(prefs.getInt(KEY_INTENTIONAL_PASSES, 3))
    val intentionalPassesCount: StateFlow<Int> = _intentionalPassesCount.asStateFlow()

    private val _timeSavedMinutes = MutableStateFlow(prefs.getInt(KEY_TIME_SAVED_MINUTES, 48))
    val timeSavedMinutes: StateFlow<Int> = _timeSavedMinutes.asStateFlow()

    private var activeBypassUntil: Long
        get() = prefs.getLong(KEY_BYPASS_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_BYPASS_UNTIL, value).apply()

    fun setShieldActive(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHIELD_ACTIVE, enabled).apply()
        _isShieldActive.value = enabled
    }

    fun setBlockShortsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SHORTS, enabled).apply()
        _isBlockShortsEnabled.value = enabled
    }

    fun setAllowFirstShortsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_FIRST_SHORTS, enabled).apply()
        _isAllowFirstShortsEnabled.value = enabled
    }

    fun addBlockedChannel(channelName: String): Boolean {
        val trimmed = channelName.trim()
        if (trimmed.isEmpty()) return false
        val current = _blockedChannels.value.toMutableSet()
        if (current.none { it.equals(trimmed, ignoreCase = true) }) {
            current.add(trimmed)
            prefs.edit().putStringSet(KEY_BLOCKED_CHANNELS, current).apply()
            _blockedChannels.value = current
            return true
        }
        return false
    }

    fun removeBlockedChannel(channelName: String) {
        val current = _blockedChannels.value.toMutableSet()
        val removed = current.removeAll { it.equals(channelName.trim(), ignoreCase = true) }
        if (removed) {
            prefs.edit().putStringSet(KEY_BLOCKED_CHANNELS, current).apply()
            _blockedChannels.value = current
        }
    }

    fun isChannelBlocked(text: String): String? {
        if (!_isShieldActive.value) return null
        val lowerText = text.lowercase()
        return _blockedChannels.value.firstOrNull { channel ->
            val cleanChannel = channel.removePrefix("@").trim().lowercase()
            cleanChannel.isNotEmpty() && lowerText.contains(cleanChannel)
        }
    }

    fun toggleShieldedApp(packageName: String) {
        val current = _shieldedApps.value.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        prefs.edit().putStringSet(KEY_SHIELDED_APPS, current).apply()
        _shieldedApps.value = current
    }

    fun recordMindfulSave() {
        val newSaves = _mindfulSavesCount.value + 1
        val newTime = _timeSavedMinutes.value + 5
        prefs.edit()
            .putInt(KEY_MINDFUL_SAVES, newSaves)
            .putInt(KEY_TIME_SAVED_MINUTES, newTime)
            .apply()
        _mindfulSavesCount.value = newSaves
        _timeSavedMinutes.value = newTime
    }

    fun recordIntentionalPass(passMinutes: Int = 5) {
        val newPasses = _intentionalPassesCount.value + 1
        val bypassExpiry = System.currentTimeMillis() + (passMinutes * 60 * 1000L)
        activeBypassUntil = bypassExpiry
        prefs.edit().putInt(KEY_INTENTIONAL_PASSES, newPasses).apply()
        _intentionalPassesCount.value = newPasses
    }

    fun isBypassActive(): Boolean {
        return System.currentTimeMillis() < activeBypassUntil
    }

    companion object {
        private const val PREFS_NAME = "relign_mindful_prefs"
        private const val KEY_SHIELD_ACTIVE = "shield_active"
        private const val KEY_BLOCK_SHORTS = "block_shorts"
        private const val KEY_ALLOW_FIRST_SHORTS = "allow_first_shorts"
        private const val KEY_BLOCKED_CHANNELS = "blocked_channels"
        private const val KEY_SHIELDED_APPS = "shielded_apps"
        private const val KEY_MINDFUL_SAVES = "mindful_saves"
        private const val KEY_INTENTIONAL_PASSES = "intentional_passes"
        private const val KEY_TIME_SAVED_MINUTES = "time_saved_minutes"
        private const val KEY_BYPASS_UNTIL = "bypass_until"

        private val defaultBlockedChannels = setOf(
            "T-Series",
            "5-Minute Crafts",
            "Cocomelon",
            "Brainrot"
        )

        private val defaultShieldedApps = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.zhiliaoapp.musically", // TikTok
            "com.twitter.android"
        )
    }
}
