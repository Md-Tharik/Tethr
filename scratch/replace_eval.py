import os
import re

file_path = r"c:\Users\tharik\OneDrive\Documents\tethr\app\src\main\java\com\example\tethr\TethrAccessibilityService.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

target = """    private fun evaluateTier() {
        val scaledTime = if (sessionRepo.isDemoMode()) cumulativeActiveMs * 60 else cumulativeActiveMs
        when {
            scaledTime >= triggerTime && currentTier < 2 -> {
                Log.d(TAG, "→ Tier 2 at ${scaledTime / 1000}s")
                currentTier = 2
                overlayManager.showTier2()
            }
            scaledTime >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs && currentTier < 3 -> {
                val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
                val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
                if (quizzesEnabled) {
                    Log.d(TAG, "→ Tier 3 at ${scaledTime / 1000}s")
                    currentTier = 3
                    var popupsToSolve = if (scaledTime >= 20 * 60_000L) 2 else 1
                    fun showQuizSequence() {
                        overlayManager.showTier3 {
                            popupsToSolve--
                            if (popupsToSolve > 0) {
                                mainHandler.postDelayed({ showQuizSequence() }, 300)
                            } else {
                                onTaskSolved()
                            }
                        }
                    }
                    showQuizSequence()
                }
            }
        }
    }"""

replacement = """    private fun evaluateTier() {
        val isDemo = sessionRepo.isDemoMode()
        
        if (isDemo) {
            // Strict Hackathon Demo Sequence (Bypasses ML triggerTime)
            when {
                cumulativeActiveMs >= 15_000L && currentTier < 3 -> {
                    val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
                    val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
                    if (quizzesEnabled) {
                        Log.d(TAG, "→ Tier 3 (Demo) at ${cumulativeActiveMs / 1000}s")
                        currentTier = 3
                        var popupsToSolve = 2 // Hardcoded 2 popups for demo
                        fun showQuizSequence() {
                            overlayManager.showTier3 {
                                popupsToSolve--
                                if (popupsToSolve > 0) {
                                    // 5 seconds between popups in demo mode
                                    mainHandler.postDelayed({ showQuizSequence() }, 5000)
                                } else {
                                    onTaskSolved()
                                }
                            }
                        }
                        showQuizSequence()
                    }
                }
                cumulativeActiveMs >= 10_000L && currentTier < 2 -> {
                    Log.d(TAG, "→ Tier 2 (Demo) at ${cumulativeActiveMs / 1000}s")
                    currentTier = 2
                    overlayManager.showTier2()
                }
            }
        } else {
            // CORE ML LOGIC (Untouched)
            val scaledTime = cumulativeActiveMs
            when {
                scaledTime >= triggerTime && currentTier < 2 -> {
                    Log.d(TAG, "→ Tier 2 at ${scaledTime / 1000}s")
                    currentTier = 2
                    overlayManager.showTier2()
                }
                scaledTime >= triggerTime + TIER2_DURATION_MS + tier3ExtensionMs && currentTier < 3 -> {
                    val prefs = getSharedPreferences("TethrPrefs", android.content.Context.MODE_PRIVATE)
                    val quizzesEnabled = prefs.getBoolean("ENABLE_QUIZZES", true)
                    if (quizzesEnabled) {
                        Log.d(TAG, "→ Tier 3 at ${scaledTime / 1000}s")
                        currentTier = 3
                        var popupsToSolve = if (scaledTime >= 20 * 60_000L) 2 else 1
                        fun showQuizSequence() {
                            overlayManager.showTier3 {
                                popupsToSolve--
                                if (popupsToSolve > 0) {
                                    mainHandler.postDelayed({ showQuizSequence() }, 300)
                                } else {
                                    onTaskSolved()
                                }
                            }
                        }
                        showQuizSequence()
                    }
                }
            }
        }
    }"""

new_content = content.replace(target, replacement)

if new_content != content:
    with open(file_path, "w", encoding="utf-8") as f:
        f.write(new_content)
    print("Successfully updated file!")
else:
    print("Could not find exact string. Target string may have mismatching spaces.")
