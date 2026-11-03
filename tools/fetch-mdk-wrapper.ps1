$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$ref = '3b47f6ef641635aae130c34d0c50c3358801ea8f'
New-Item -ItemType Directory -Force "$repo\gradle\wrapper" | Out-Null
Invoke-WebRequest "https://raw.githubusercontent.com/NeoForged/MDK/$ref/gradle/wrapper/gradle-wrapper.jar" -OutFile "$repo\gradle\wrapper\gradle-wrapper.jar"
Invoke-WebRequest "https://raw.githubusercontent.com/NeoForged/MDK/$ref/gradlew" -OutFile "$repo\gradlew"
Invoke-WebRequest "https://raw.githubusercontent.com/NeoForged/MDK/$ref/gradlew.bat" -OutFile "$repo\gradlew.bat"
Get-ChildItem "$repo\gradle\wrapper", "$repo\gradlew", "$repo\gradlew.bat" | Select-Object Name, Length
