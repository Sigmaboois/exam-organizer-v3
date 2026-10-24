@echo off
rem Double-click to build the Android app: dist\Exam Organizer.apk
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1" %*
echo.
pause
