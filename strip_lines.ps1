$inputPath = "scratch\overlay_var_8.kt"
$outputPath = "app\src\main\java\com\example\tethr\OverlayManager.kt"
$lines = Get-Content $inputPath -Encoding UTF8
$newLines = @()
foreach ($line in $lines) {
    if ($line -match '^\d+:\s?(.*)$') {
        $newLines += $matches[1]
    }
}
Set-Content $outputPath -Value $newLines -Encoding UTF8
Write-Host "Restored OverlayManager.kt without line numbers."

$inputPath2 = "scratch\tethr_var_11.kt"
$outputPath2 = "app\src\main\java\com\example\tethr\TethrAccessibilityService.kt"
$lines2 = Get-Content $inputPath2 -Encoding UTF8
$newLines2 = @()
foreach ($line in $lines2) {
    if ($line -match '^\d+:\s?(.*)$') {
        $newLines2 += $matches[1]
    }
}
Set-Content $outputPath2 -Value $newLines2 -Encoding UTF8
Write-Host "Restored TethrAccessibilityService.kt without line numbers."
