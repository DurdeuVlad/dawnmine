param([string]$Path)
Select-String -Path $Path -Pattern '^(diff |deleted file|new file|index )' | ForEach-Object { $_.Line }
