$filePath = "c:\Users\tharik\OneDrive\Documents\tethr\app\src\main\java\com\example\tethr\ui\main\MainScreen.kt"
$content = Get-Content -Path $filePath -Raw

$target1 = @"
    var triggerTime by remember { mutableLongStateOf(300_000L) }

    var pendingConnPort by remember { mutableStateOf<Int?>(null) }
"@

$replacement1 = @"
    var triggerTime by remember { mutableLongStateOf(300_000L) }
    var timeSavedTodayMs by remember { mutableLongStateOf(0L) }
    var todayUsageMs by remember { mutableLongStateOf(0L) }
    var avg10DayMs by remember { mutableLongStateOf(0L) }

    var pendingConnPort by remember { mutableStateOf<Int?>(null) }
"@

$target2 = @"
            sessionHistory = repo.getSessionHistory()
            weekCount = repo.getSessionCountThisWeek()
            triggerTime = repo.computeTriggerTime()
            delay(1000)
"@

$replacement2 = @"
            sessionHistory = repo.getSessionHistory()
            weekCount = repo.getSessionCountThisWeek()
            triggerTime = repo.computeTriggerTime()
            timeSavedTodayMs = repo.getTimeSavedTodayMs()
            todayUsageMs = repo.getTodayUsageMs()
            avg10DayMs = repo.get10DayAverageMs()
            delay(1000)
"@

$target3 = @"
            // ── Stats Row ─────────────────────────────────────────────────
            StatsRow(
"@

$replacement3 = @"
            // ── Time Reclaimed ────────────────────────────────────────────
            if (timeSavedTodayMs > 0L) {
                TimeReclaimedCard(timeSavedTodayMs = timeSavedTodayMs, todayUsageMs = todayUsageMs, avg10DayMs = avg10DayMs)
                Spacer(Modifier.height(16.dp))
            } else if (todayUsageMs > avg10DayMs) {
                TimeExceededCard(todayUsageMs = todayUsageMs, avg10DayMs = avg10DayMs)
                Spacer(Modifier.height(16.dp))
            }

            // ── Stats Row ─────────────────────────────────────────────────
            StatsRow(
"@

$target4 = @"
@Composable
private fun StatsRow(
"@

$replacement4 = @"
@Composable
private fun TimeReclaimedCard(timeSavedTodayMs: Long, todayUsageMs: Long, avg10DayMs: Long) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1B2F22), Color(0xFF111F15))))
            .border(1.dp, Color(0xFF2E5136), RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Star, contentDescription = null, tint = TethrGreen, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Time Reclaimed Today", color = TethrGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(formatDuration(timeSavedTodayMs), color = Color.White, fontWeight = FontWeight.Black, fontSize = 36.sp)
        Spacer(Modifier.height(8.dp))
        Text("Your 10-day average is ${formatDuration(avg10DayMs)}. You've only spent ${formatDuration(todayUsageMs)} today. Keep going!", 
            color = Color(0xFFAAAAAA), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TimeExceededCard(todayUsageMs: Long, avg10DayMs: Long) {
    val exceededBy = todayUsageMs - avg10DayMs
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A1C1C), Color(0xFF221111))))
            .border(1.dp, Color(0xFF5A2A2A), RoundedCornerShape(16.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = TethrRed, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text("Baseline Exceeded", color = TethrRed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text("+${formatDuration(exceededBy)}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 36.sp)
        Spacer(Modifier.height(8.dp))
        Text("You are over your ${formatDuration(avg10DayMs)} daily average. The AI Trigger has tightened.", 
            color = Color(0xFFAAAAAA), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatsRow(
"@


$newContent = $content.Replace($target1, $replacement1)
$newContent = $newContent.Replace($target2, $replacement2)
$newContent = $newContent.Replace($target3, $replacement3)
$newContent = $newContent.Replace($target4, $replacement4)

if ($newContent -ne $content) {
    Set-Content -Path $filePath -Value $newContent -Encoding UTF8
    Write-Host "Replaced successfully"
} else {
    Write-Host "Target not found"
}
