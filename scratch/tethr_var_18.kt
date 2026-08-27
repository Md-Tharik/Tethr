@"
$log = "C:\Users\tharik\.gemini\antigravity-ide\brain\8530c733-4810-4cad-90d1-79ab33d6871e\.system_generated\logs\transcript_full.jsonl"
$lines = Get-Content $log -Encoding UTF8
$tethrCount = 0
$overlayCount = 0

foreach ($line in $lines) {
    if ($line -match 'class TethrAccessibilityService' -and $line -match 'package com.example.tethr') {
        try {
            $json = $line | ConvertFrom-Json
            $content = ""
            if ($json.tool_calls) {
                foreach ($call in $json.tool_calls) {
                    if ($call.args.CodeContent) { $content = $call.args.CodeContent }
                    elseif ($call.args.CommandLine) { $content = $call.args.CommandLine }
                }
            } elseif ($json.content) {
                $content = $json.content
            }
            if ($content.Length -gt 1000) {
                Set-Content -Path "scratch\tethr_var_$tethrCount.kt" -Value $content -Encoding UTF8
                $tethrCount++
            }
        } catch {}
    }
    
    if ($line -match 'class OverlayManager' -and $line -match 'package com.example.tethr') {
        try {
            $json = $line | ConvertFrom-Json
            $content = ""
            if ($json.tool_calls) {
                foreach ($call in $json.tool_calls) {
                    if ($call.args.CodeContent) { $content = $call.args.CodeContent }
                    elseif ($call.args.CommandLine) { $content = $call.args.CommandLine }
                }
            } elseif ($json.content) {
                $content = $json.content
            }
            if ($content.Length -gt 1000) {
                Set-Content -Path "scratch\overlay_var_$overlayCount.kt" -Value $content -Encoding UTF8
                $overlayCount++
            }
        } catch {}
    }
}
Write-Host "Extracted $tethrCount Tethr versions and $overlayCount OverlayManager versions."
"@ | Out-File -FilePath "extract_all_versions.ps1" -Encoding UTF8
.\extract_all_versions.ps1
