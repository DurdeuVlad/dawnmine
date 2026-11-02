$ErrorActionPreference = 'Stop'
Set-Location 'C:\Users\Vlad\Documents\Github\rusticcraft-public-mines'
$files = @(
    'Progression.md',
    '.devin/product-owner-checklist.md',
    'docs/issues/M1-admin-onboarding.md',
    'docs/issues/M6-progression-economy.md'
)
foreach ($f in $files) {
    $upstream = git show "origin/main:$f" 2>$null
    $local = Get-Content $f -Raw
    $upText = ($upstream -join "`n") + "`n"
    $localNorm = ($local -replace "`r`n", "`n")
    if ($upText -eq $localNorm) { Write-Output "IDENTICAL  $f" } else { Write-Output "DIFFERS    $f" }
}
