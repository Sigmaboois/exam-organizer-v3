@echo off
rem Build and install on the connected iPhone/iPad. Keep the result visible.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-iphone.ps1" %*
set "INSTALL_RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %INSTALL_RESULT%
