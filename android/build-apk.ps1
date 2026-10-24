# Builds the Android app (APK) inside Ubuntu (WSL), step by step, with a log.
# Double-click build-apk.cmd, or run:  powershell -ExecutionPolicy Bypass -File android\build-apk.ps1
# The APK ends up in dist\Exam Organizer.apk: send it to an Android phone and open it to install.
# Add "test" at the end to also run the paper-detection tests first.

$here = $PSScriptRoot
$logDir = Join-Path $here "logs"
New-Item -ItemType Directory -Force $logDir | Out-Null
$logName = "build-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss').log"
$logFile = Join-Path $logDir $logName

function Log($text, $colour = "Gray") {
    $line = "[$(Get-Date -Format 'HH:mm:ss')] $text"
    Write-Host $line -ForegroundColor $colour
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

function Fail($text) {
    Log "PROBLEM: $text" "Red"
    Log "Log saved to $logFile" "Yellow"
    exit 1
}

Log "Exam Organizer Android builder" "Cyan"
Log "Log file: $logFile"

Log "[1/2] Checking WSL and Ubuntu..." "Cyan"
$distros = (wsl -l -q 2>$null | Out-String) -replace "`0", ""
if ($distros -notmatch "Ubuntu") { Fail "WSL's Ubuntu isn't installed." }
Log "      OK: Ubuntu is installed." "Green"

Log "[2/2] Building inside Ubuntu (a few minutes the first time)..." "Cyan"
$mode = if ($args -contains "test") { "test" } else { "build" }
wsl -d Ubuntu -u root --cd $here -- bash scripts/build-apk.sh "logs/$logName" $mode
if ($LASTEXITCODE -ne 0) { Fail "Building failed (exit code $LASTEXITCODE). See the messages above." }
$apk = Join-Path (Split-Path $here -Parent) "dist\Exam Organizer.apk"
Log "All done! The app is $apk" "Green"
Log "Send it to an Android phone (WhatsApp, Drive, email or a cable) and open it to install." "Green"
Log "Log saved to $logFile" "Yellow"
