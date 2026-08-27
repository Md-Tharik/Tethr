package com.example.tethr.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException

/**
 * SessionRepository
 *
 * Manages all session-level persistence for Tethr:
 * - Tracks past session durations (up to last 10) for the predictive ML timer
 * - Computes the User_Local_Average from historical data
 * - Applies the dynamic trigger equation:
 *       TriggerTime = (300_000ms * 0.3) + (User_Local_Average * 0.7)
 *       Minimum floor: 120_000ms (2 minutes)
 * - Saves / loads cumulative active time and last-exit timestamp
 *   for 10-minute cooldown logic
 */
class SessionRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Dynamic Trigger Time (Predictive ML)
    // ------------------------------------------------------------------

    /**
     * Reads the list of past completed session durations (in ms) from prefs.
     * Stored as a JSON array string to survive app process death.
     */
    fun getSessionHistory(): List<Long> {
        val raw = prefs.getString(KEY_SESSION_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> arr.getLong(i) }
        } catch (e: JSONException) {
            Log.w(TAG, "Corrupt session history, resetting: ${e.message}")
            emptyList()
        }
    }

    /**
     * Appends a completed session duration to the rolling history.
     * Only the last [MAX_HISTORY_SIZE] sessions are kept.
     */
    fun recordSession(durationMs: Long) {
        if (durationMs < MIN_RECORDABLE_SESSION_MS) return // ignore sub-2-min sessions
        val history = getSessionHistory().toMutableList()
        history.add(durationMs)
        // Keep only the most recent N sessions
        val trimmed = if (history.size > MAX_HISTORY_SIZE) {
            history.takeLast(MAX_HISTORY_SIZE)
        } else history
        val arr = JSONArray().apply { trimmed.forEach { put(it) } }
        prefs.edit().putString(KEY_SESSION_HISTORY, arr.toString()).apply()
        
        addDailyUsageMs(durationMs)
        
        Log.d(TAG, "Session recorded: ${durationMs}ms. History size: ${trimmed.size}")
    }

    /**
     * Computes the arithmetic mean of all past session durations.
     * Returns 0 if no history exists (first-time user).
     */
    fun computeUserLocalAverage(): Long {
        val history = getSessionHistory()
        return if (history.isEmpty()) 0L else history.average().toLong()
    }

    /**
     * Computes the dynamic grayscale trigger time using the Inverse ML equation:
     *   Target Average: 5 mins -> Trigger: 20 mins (Max Reward)
     *   Max Average: 20 mins -> Trigger: 2 mins (Max Punishment)
     */
    fun computeTriggerTime(): Long {
        val average = computeUserLocalAverage()
        
        val excessAverageMs = maxOf(0L, average - TARGET_AVERAGE_MS)
        val maxExcessMs = MAX_AVERAGE_MS - TARGET_AVERAGE_MS
        
        // Ensure factor is between 0.0 and 1.0
        val penaltyFactor = minOf(excessAverageMs.toDouble() / maxExcessMs.toDouble(), 1.0)
        
        val penaltyMs = (penaltyFactor * (MAX_TRIGGER_MS - MIN_TRIGGER_MS)).toLong()
        val result = MAX_TRIGGER_MS - penaltyMs
        
        Log.d(TAG, "TriggerTime computed: ${result}ms (avg=${average}ms, penaltyFactor=$penaltyFactor)")
        return result
    }

    // ------------------------------------------------------------------
    // Active Session State â€” Survives OS Process Kill
    // ------------------------------------------------------------------

    /**
     * Atomically saves the cumulative active time and the timestamp of when
     * the user last left Instagram. Called the instant Instagram is backgrounded.
     */
    fun saveActiveState(cumulativeMs: Long, lastExitTimestamp: Long) {
        prefs.edit()
            .putLong(KEY_CUMULATIVE_ACTIVE_MS, cumulativeMs)
            .putLong(KEY_LAST_EXIT_TIMESTAMP, lastExitTimestamp)
            .commit() // commit() not apply() â€” we need this to be synchronous
    }

    /**
     * Loads the previously saved cumulative time and exit timestamp.
     * Returns Pair(cumulativeMs, lastExitTimestamp).
     */
    fun loadActiveState(): Pair<Long, Long> {
        val cumulative = prefs.getLong(KEY_CUMULATIVE_ACTIVE_MS, 0L)
        val lastExit = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        return Pair(cumulative, lastExit)
    }

    /** Resets all active session state (called after 10-min cooldown). */
    fun resetActiveState() {
        prefs.edit()
            .putLong(KEY_CUMULATIVE_ACTIVE_MS, 0L)
            .putLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
            .commit()
        Log.d(TAG, "Active state reset (10-min cooldown triggered)")
    }

    // ------------------------------------------------------------------
    // Demo Mode Helper
    // ------------------------------------------------------------------

    fun isDemoMode(): Boolean = prefs.getBoolean(KEY_DEMO_MODE, false)

    // ------------------------------------------------------------------
    // Daily Baseline & Time Saved
    // ------------------------------------------------------------------

    private fun getCurrentDateStr(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    private fun getDailyUsageMap(): MutableMap<String, Long> {
        val raw = prefs.getString(KEY_DAILY_USAGE, "{}") ?: "{}"
        val map = mutableMapOf<String, Long>()
        try {
            val json = org.json.JSONObject(raw)
            json.keys().forEach { key ->
                map[key] = json.getLong(key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing daily usage", e)
        }
        return map
    }

    private fun saveDailyUsageMap(map: Map<String, Long>) {
        val json = org.json.JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_DAILY_USAGE, json.toString()).apply()
    }

    fun addDailyUsageMs(durationMs: Long) {
        val today = getCurrentDateStr()
        val map = getDailyUsageMap()
        val current = map[today] ?: 0L
        map[today] = current + durationMs
        
        // Keep only last 11 days (10 historical + 1 today)
        val trimmedMap = if (map.size > 11) {
            val sortedKeys = map.keys.sortedDescending().take(11)
            map.filterKeys { it in sortedKeys }.toMutableMap()
        } else map
        
        saveDailyUsageMap(trimmedMap)
    }

    fun get10DayAverageMs(): Long {
        val today = getCurrentDateStr()
        val map = getDailyUsageMap()
        val historicalKeys = map.keys.filter { it != today }.sortedDescending().take(10)
        
        if (historicalKeys.isEmpty()) {
            // Fallback for new users: assume 4 hours baseline
            return 4L * 60 * 60 * 1000L
        }
        
        var total = 0L
        historicalKeys.forEach { total += (map[it] ?: 0L) }
        return total / historicalKeys.size
    }

    fun getTodayUsageMs(): Long {
        val today = getCurrentDateStr()
        val map = getDailyUsageMap()
        return map[today] ?: 0L
    }

    fun getTimeSavedTodayMs(): Long {
        val avg = get10DayAverageMs()
        val today = getTodayUsageMs()
        return maxOf(0L, avg - today)
    }

    fun hasBaselineData(): Boolean {
        val today = getCurrentDateStr()
        val map = getDailyUsageMap()
        val historicalKeys = map.keys.filter { it != today }
        return historicalKeys.isNotEmpty()
    }

    // ------------------------------------------------------------------
    // Stats for Dashboard
    // ------------------------------------------------------------------

    /** Returns the number of sessions recorded this calendar week. */
    fun getSessionCountThisWeek(): Int {
        // Stored as a simple int, reset on Monday midnight (best-effort)
        return prefs.getInt(KEY_WEEK_SESSION_COUNT, 0)
    }

    fun incrementWeekSessionCount() {
        val current = prefs.getInt(KEY_WEEK_SESSION_COUNT, 0)
        prefs.edit().putInt(KEY_WEEK_SESSION_COUNT, current + 1).apply()
    }

    companion object {
        const val PREFS_NAME = "TethrPrefs"

        // Keys
        private const val KEY_SESSION_HISTORY = "SESSION_HISTORY"
        private const val KEY_CUMULATIVE_ACTIVE_MS = "CUMULATIVE_ACTIVE_MS"
        private const val KEY_LAST_EXIT_TIMESTAMP = "LAST_EXIT_TIMESTAMP"
        private const val KEY_DEMO_MODE = "DEMO_MODE"
        private const val KEY_WEEK_SESSION_COUNT = "WEEK_SESSION_COUNT"
        private const val KEY_DAILY_USAGE = "DAILY_USAGE"

        // ML Equation constants
        private const val MIN_TRIGGER_MS = 2 * 60_000L      // 2 minutes floor (max punishment)
        private const val MAX_TRIGGER_MS = 20 * 60_000L     // 20 minutes ceiling (max reward)
        private const val TARGET_AVERAGE_MS = 5 * 60_000L   // 5 minutes ideal session
        private const val MAX_AVERAGE_MS = 20 * 60_000L     // 20 minutes addicted session
        private const val MAX_HISTORY_SIZE = 10             // rolling window
        private const val MIN_RECORDABLE_SESSION_MS = 30_000L // ignore <30s micro-opens

        // Cooldown
        const val COOLDOWN_MS = 10 * 60 * 1000L        // 10 minutes

        private const val TAG = "SessionRepository"
    }
}

