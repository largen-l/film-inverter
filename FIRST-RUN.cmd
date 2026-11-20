@echo off
rem Double-click me. Installs the toolchain into toolchain\ and builds the apps.
rem Everything is logged to logs\first-run.log. Takes ~10-20 minutes the first time.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\first-run.ps1"
echo.
echo Done. Log: %~dp0logs\first-run.log
pause
