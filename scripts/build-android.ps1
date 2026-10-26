param(
    [string]$ToolchainRoot = '',
    [ValidateSet('Debug','Release')][string]$BuildType = 'Debug'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ($ToolchainRoot) {
    $env:JAVA_HOME = Join-Path $ToolchainRoot 'jdk\jdk-17.0.20.1+1'
    $env:ANDROID_HOME = Join-Path $ToolchainRoot 'sdk'
    $gradleBinary = Join-Path $ToolchainRoot 'gradle\gradle-8.9\bin\gradle.bat'
} else {
    $gradleBinary = (Get-Command gradle -ErrorAction Stop).Source
}
if (!$env:ANDROID_HOME) { throw 'Set ANDROID_HOME or pass -ToolchainRoot.' }
Set-Content -LiteralPath (Join-Path $projectRoot 'android\local.properties') -Value ('sdk.dir=' + $env:ANDROID_HOME.Replace('\','/')) -Encoding ascii
Push-Location (Join-Path $projectRoot 'android')
try {
    & $gradleBinary --no-daemon ":app:assemble$BuildType"
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }

