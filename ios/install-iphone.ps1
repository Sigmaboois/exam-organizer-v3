# Builds Exam Organizer and installs it on the plugged-in iPhone, step by step, with a log.
# Double-click install-iphone.cmd, or run:  powershell -ExecutionPolicy Bypass -File ios\install-iphone.ps1
# Add "debug" at the end for a debug build. Logs are saved in ios\logs\.

$here = $PSScriptRoot
$logDir = Join-Path $here "logs"
New-Item -ItemType Directory -Force $logDir | Out-Null
$logName = "install-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss').log"
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

Log "Exam Organizer iPhone installer" "Cyan"
Log "Log file: $logFile"

# 1. WSL with Ubuntu
Log "[1/5] Checking WSL and Ubuntu..." "Cyan"
$distros = (wsl -l -q 2>$null | Out-String) -replace "`0", ""
if ($distros -notmatch "Ubuntu") { Fail "WSL's Ubuntu isn't installed." }
Log "      OK: Ubuntu is installed." "Green"

# 2. WSL networking: VirtioProxy reaches both the internet and Apple's iPhone service
Log "[2/5] Checking WSL networking..." "Cyan"
$config = Join-Path $env:USERPROFILE ".wslconfig"
if (-not (Test-Path $config) -or -not (Select-String -Path $config -Pattern "networkingMode=VirtioProxy" -Quiet)) {
    Fail "$config must contain networkingMode=VirtioProxy under [wsl2]. Fix it, run 'wsl --shutdown', then try again."
}
Log "      OK: networkingMode=VirtioProxy." "Green"

# 3. Kaspersky VPN stops WSL from reaching Apple's servers
Log "[3/5] Checking the VPN..." "Cyan"
$vpn = Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceDescription -match "Kaspersky" -and $_.Status -eq "Up" }
if ($vpn) { Fail "Kaspersky VPN is on. Turn it off, then run this again (turn it back on afterwards)." }
Log "      OK: VPN is off." "Green"

# 4. Apple's iPhone service comes with the Apple Devices app (Microsoft Store)
Log "[4/5] Checking Apple's iPhone service..." "Cyan"
function Test-AppleService { [bool](Get-NetTCPConnection -LocalPort 27015 -State Listen -ErrorAction SilentlyContinue) }
if (-not (Test-AppleService)) {
    $package = Get-AppxPackage AppleInc.AppleDevices
    if (-not $package) { Fail "Install 'Apple Devices' from the Microsoft Store first." }
    Log "      Starting the Apple Devices app..."
    $appId = (Get-AppxPackageManifest $package).Package.Applications.Application | Select-Object -First 1 -ExpandProperty Id
    Start-Process "shell:AppsFolder\$($package.PackageFamilyName)!$appId"
    for ($i = 0; $i -lt 30 -and -not (Test-AppleService); $i++) { Start-Sleep -Seconds 1 }
    if (-not (Test-AppleService)) { Fail "Apple's iPhone service didn't start. Open the Apple Devices app yourself, then try again." }
}
Log "      OK: Apple's iPhone service is running." "Green"

# 5. Build and install inside Ubuntu (it adds its own output to the same log)
Log "[5/5] Building and installing inside Ubuntu..." "Cyan"
wsl -d Ubuntu -u root --cd $here -- bash scripts/install-iphone.sh "logs/$logName" @args
if ($LASTEXITCODE -ne 0) { Fail "Building or installing failed (exit code $LASTEXITCODE). See the messages above." }
Log "All done! Open Exam Organizer on the iPhone." "Green"
Log "Log saved to $logFile" "Yellow"
