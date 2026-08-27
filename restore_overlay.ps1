$log = "C:\Users\tharik\.gemini\antigravity-ide\brain\8530c733-4810-4cad-90d1-79ab33d6871e\.system_generated\logs\transcript_full.jsonl"
$lines = Get-Content $log -Encoding UTF8

$content = ""
foreach ($line in $lines) {
    if ($line -match '"name":"write_to_file"' -and $line -match '"TargetFile".*OverlayManager.kt') {
        try {
            $json = $line | ConvertFrom-Json
            if ($json.tool_calls) {
                foreach ($call in $json.tool_calls) {
                    if ($call.name -eq 'write_to_file' -and $call.args.TargetFile -match 'OverlayManager.kt') {
                        $content = $call.args.CodeContent
                    }
                }
            }
        } catch {}
    }
}
if ($content) {
    Set-Content "app\src\main\java\com\example\tethr\OverlayManager.kt" -Value $content -Encoding UTF8
    Write-Host "Found and restored OverlayManager.kt"
} else {
    Write-Host "Not found"
}
