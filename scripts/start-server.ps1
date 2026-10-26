param([string]$LanAddress, [int]$Port=8743)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pythonBinary = Join-Path $projectRoot '.venv\Scripts\python.exe'
if (!(Test-Path -LiteralPath $pythonBinary)) { throw 'Create the project .venv and install requirements first.' }
if (!$LanAddress) {
    $config = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address } | Select-Object -First 1
    $LanAddress = $config.IPv4Address.IPAddress
}
if (!$LanAddress) { throw 'No LAN interface found; provide -LanAddress.' }
Push-Location $projectRoot
try { & $pythonBinary -m roomscan.cli serve --advertise $LanAddress --port $Port }
finally { Pop-Location }

