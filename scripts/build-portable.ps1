param([string]$BrushDirectory = '', [string]$Version = '0.6.0-preview')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$BrushDirectory) { $BrushDirectory = Join-Path $projectRoot '.tools/brush' }
Push-Location $projectRoot
try {
    $pythonBinary = Join-Path $projectRoot '.venv/Scripts/python.exe'
    & $pythonBinary scripts/draw-icon.py
    if ($LASTEXITCODE -ne 0) { throw 'Icon generation failed' }
    & $pythonBinary scripts/collect-licenses.py
    if ($LASTEXITCODE -ne 0) { throw 'License collection failed' }
    & $pythonBinary -m PyInstaller --noconfirm --clean --onedir --windowed --name RoomScan --icon "$projectRoot/assets/roomscan.ico" --paths "$projectRoot/server" --collect-all roomscan --exclude-module pycolmap --hidden-import numpy --collect-all zeroconf --collect-submodules uvicorn --hidden-import PIL._tkinter_finder --add-data "$projectRoot/assets;assets" --distpath dist/windows --workpath .runtime/portable-build --specpath .runtime scripts/desktop.py
    if ($LASTEXITCODE -ne 0) { throw 'Portable build failed' }
    $bundle = Join-Path $projectRoot 'dist/windows/RoomScan'
    if (!(Test-Path -LiteralPath "$BrushDirectory/brush_app.exe")) { throw 'Brush binary required; see docs/BUILDING.md' }
    New-Item -ItemType Directory -Force "$bundle/.tools/brush" | Out-Null
    Copy-Item -LiteralPath "$BrushDirectory/brush_app.exe","$BrushDirectory/LICENSE","$BrushDirectory/README.md" -Destination "$bundle/.tools/brush"
    Copy-Item -LiteralPath LICENSE,THIRD_PARTY_NOTICES.md -Destination $bundle
    Copy-Item -LiteralPath licenses -Destination $bundle -Recurse -Force
    Copy-Item -LiteralPath docs/PORTABLE.txt -Destination "$bundle/README.txt"
    & $pythonBinary scripts/archive-portable.py $bundle "dist/RoomScan-$Version-windows-x64.zip"
    if ($LASTEXITCODE -ne 0) { throw 'Portable archive failed' }
} finally { Pop-Location }
