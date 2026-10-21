@echo off
rem Double-click to build Exam Organizer and install it on the plugged-in iPhone.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-iphone.ps1" %*
echo.
pause
