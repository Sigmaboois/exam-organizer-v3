# Builds the Windows app.
#
# Visual Studio's C++ compiler can't handle paths longer than 260 characters, and
# Flutter's build folders are deeply nested, so building inside this project folder
# fails. The app files are copied to a very short folder and built there instead.
# The finished app ends up in .\dist\windows
#
# Usage (from the project folder):  powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$work = Join-Path $env:SystemDrive "eo-build"
$output = Join-Path $project "dist\windows"

Write-Host "Copying app files to $work ..."
New-Item -ItemType Directory -Force $work | Out-Null
robocopy $project $work main.py pyproject.toml /NJH /NJS /NP /NFL /NDL | Out-Null
robocopy (Join-Path $project "src") (Join-Path $work "src") /MIR /XD __pycache__ /NJH /NJS /NP /NFL /NDL | Out-Null
if (Test-Path (Join-Path $project "assets")) {
    robocopy (Join-Path $project "assets") (Join-Path $work "assets") /MIR /NJH /NJS /NP /NFL /NDL | Out-Null
}

$scripts = & py -c "import sysconfig; print(sysconfig.get_path('scripts'))"
$flet = Join-Path $scripts "flet.exe"
if (-not (Test-Path $flet)) { throw "flet isn't installed. Run: py -m pip install `"flet[all]`" flet-dropzone" }

Write-Host "Building (the first build takes a while) ..."
Push-Location $work
try {
    & $flet build windows --yes -o $output
    if ($LASTEXITCODE -ne 0) { throw "flet build failed (exit code $LASTEXITCODE)" }
} finally {
    Pop-Location
}
Write-Host "Done! The app is in $output"
