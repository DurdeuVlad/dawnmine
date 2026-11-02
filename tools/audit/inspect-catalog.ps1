$ErrorActionPreference = 'Stop'
$catalogPath = 'C:\Users\Vlad\Documents\Github\rustic-craft-2-modpack\catalog\mods.json'
$mods = Get-Content $catalogPath -Raw | ConvertFrom-Json
Write-Output ('total mods in catalog: ' + $mods.Count)
Write-Output '--- name matches: chaos/gecko/born/kubejs/rhino ---'
$mods | Where-Object { $_.name -match 'chaos|gecko|born|kubejs|rhino' } | ForEach-Object {
    $_.name + ' [side=' + $_.side + ' modId=' + $_.modId + ' fileId=' + $_.fileId + ']'
}
