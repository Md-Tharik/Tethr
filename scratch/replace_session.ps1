$filePath = "c:\Users\tharik\OneDrive\Documents\tethr\app\src\main\java\com\example\tethr\data\SessionRepository.kt"
$content = Get-Content -Path $filePath -Raw

$target1 = @"
    // ------------------------------------------------------------------
    // Stats for Dashboard
    // ------------------------------------------------------------------
"@

$replacement1 = @"
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

    // ------------------------------------------------------------------
    // Stats for Dashboard
    // ------------------------------------------------------------------
"@

$target2 = @"
        val arr = JSONArray().apply { trimmed.forEach { put(it) } }
        prefs.edit().putString(KEY_SESSION_HISTORY, arr.toString()).apply()
        Log.d(TAG, "Session recorded: `${durationMs}ms. History size: `${trimmed.size}")
"@

$replacement2 = @"
        val arr = JSONArray().apply { trimmed.forEach { put(it) } }
        prefs.edit().putString(KEY_SESSION_HISTORY, arr.toString()).apply()
        
        addDailyUsageMs(durationMs)
        
        Log.d(TAG, "Session recorded: `${durationMs}ms. History size: `${trimmed.size}")
"@

$target3 = @"
        private const val KEY_WEEK_SESSION_COUNT = "WEEK_SESSION_COUNT"
"@

$replacement3 = @"
        private const val KEY_WEEK_SESSION_COUNT = "WEEK_SESSION_COUNT"
        private const val KEY_DAILY_USAGE = "DAILY_USAGE"
"@

$newContent = $content.Replace($target1, $replacement1)
$newContent = $newContent.Replace($target2, $replacement2)
$newContent = $newContent.Replace($target3, $replacement3)

if ($newContent -ne $content) {
    Set-Content -Path $filePath -Value $newContent -Encoding UTF8
    Write-Host "Replaced successfully"
} else {
    Write-Host "Target not found"
}
