# Compatibility launcher for the updated installer.
& (Join-Path $PSScriptRoot "install-iphone.ps1") @args
exit $LASTEXITCODE
