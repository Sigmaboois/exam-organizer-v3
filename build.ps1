# Builds the Windows app and its installer.
#
# Visual Studio's C++ compiler can't handle paths longer than 260 characters, and
# Flutter's build folders are deeply nested, so building inside this project folder
# fails. The app files are copied to a very short folder and built there instead.
#
#   .\dist\windows\                         the app itself
#   .\dist\Exam Organizer Setup X.Y.Z.exe   one-file installer to send to people
#
# Usage (from the project folder):  powershell -ExecutionPolicy Bypass -File build.ps1

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot
$work = Join-Path $env:SystemDrive "eo-build"
$output = Join-Path $project "dist\windows"

# Checking papers needs the Gemini key bundled in, so make sure it's there
$keyFile = Join-Path $project "src\api_key.py"
if (-not (Test-Path $keyFile) -or (Get-Content $keyFile -Raw) -match 'GEMINI_API_KEY\s*=\s*""') {
    Write-Warning "src\api_key.py has no Gemini key, so checking papers won't work in this build."
}

Write-Host "Copying app files to $work ..."
New-Item -ItemType Directory -Force $work | Out-Null
robocopy $project $work main.py pyproject.toml /NJH /NJS /NP /NFL /NDL | Out-Null
# Leave out caches and any local data folders: the app creates its own
robocopy (Join-Path $project "src") (Join-Path $work "src") /MIR /XD __pycache__ data /NJH /NJS /NP /NFL /NDL | Out-Null
Remove-Item -Recurse -Force (Join-Path $work "src\data") -ErrorAction SilentlyContinue  # robocopy keeps excluded folders
if (Test-Path (Join-Path $project "assets")) {
    robocopy (Join-Path $project "assets") (Join-Path $work "assets") /MIR /NJH /NJS /NP /NFL /NDL | Out-Null
}

$scripts = & py -c "import sysconfig; print(sysconfig.get_path('scripts'))"
$flet = Join-Path $scripts "flet.exe"
if (-not (Test-Path $flet)) { throw "flet isn't installed. Run: py -m pip install `"flet[all]`" flet-dropzone" }

# Start from an empty output folder so no old files end up in the installer
if (Test-Path $output) { Remove-Item -Recurse -Force $output }

Write-Host "Building the app (the first build takes a while) ..."
Push-Location $work
try {
    & $flet build windows --yes -o $output
    if ($LASTEXITCODE -ne 0) { throw "flet build failed (exit code $LASTEXITCODE)" }
} finally {
    Pop-Location
}
Write-Host "App built in $output"

# Pack everything into one installer with Inno Setup (free: winget install JRSoftware.InnoSetup)
$iscc = @(
    (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe"),
    (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
    (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe")
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if ($iscc) {
    Write-Host "Creating the installer ..."
    & $iscc /Q (Join-Path $project "installer.iss")
    if ($LASTEXITCODE -ne 0) { throw "Creating the installer failed (exit code $LASTEXITCODE)" }
    Write-Host "Done! Send people the installer in $(Join-Path $project 'dist')"
} else {
    Write-Warning "Inno Setup isn't installed, so no installer was made. Install it with: winget install JRSoftware.InnoSetup"
}
