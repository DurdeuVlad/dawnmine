param([string]$File)
Set-Location 'C:\Users\Vlad\Documents\Github\rusticcraft-public-mines'
$tmpUp = Join-Path $env:TEMP 'up-check.txt'
git show "origin/main:$File" | Out-File -Encoding utf8 $tmpUp
$up = (Get-Content $tmpUp -Raw) -replace "`r`n", "`n"
$lo = (Get-Content $File -Raw) -replace "`r`n", "`n"
if ($up -eq $lo) { Write-Output "IDENTICAL(normalized) $File"; return }
Write-Output "REAL DIFF in $File"
$upLines = $up -split "`n"
$loLines = $lo -split "`n"
$max = [Math]::Max($upLines.Count, $loLines.Count)
$shown = 0
for ($i = 0; $i -lt $max -and $shown -lt 20; $i++) {
    $u = if ($i -lt $upLines.Count) { $upLines[$i] } else { '<eof>' }
    $l = if ($i -lt $loLines.Count) { $loLines[$i] } else { '<eof>' }
    if ($u -ne $l) {
        Write-Output ("line {0}:`n  UP: {1}`n  LO: {2}" -f ($i + 1), $u, $l)
        $shown++
    }
}
