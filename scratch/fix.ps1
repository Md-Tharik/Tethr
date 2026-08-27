$files = Get-ChildItem -Path "app/src/main/java/com/example/tethr" -Recurse -Filter "*.kt"
foreach ($f in $files) {
    $content = Get-Content $f.FullName -Raw
    $changed = $false
    
    if ($content -match 'â€”') { $content = $content -replace 'â€”', '—'; $changed = $true }
    if ($content -match 'â†’') { $content = $content -replace 'â†’', '→'; $changed = $true }
    if ($content -match 'Ã—') { $content = $content -replace 'Ã—', '×'; $changed = $true }
    if ($content -match 'Â·') { $content = $content -replace 'Â·', '·'; $changed = $true }
    
    if ($changed) {
        Set-Content -Path $f.FullName -Value $content -Encoding UTF8
        Write-Host "Fixed $($f.Name)"
    }
}
