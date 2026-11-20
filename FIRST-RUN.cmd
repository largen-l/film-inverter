@echo off
rem Double-click me (Windows, from a source checkout): sets up the toolchain and builds the camera app.
rem Needs Python 3.9+ (python.org). The same steps on any OS: python tool/run.py setup / build / gui
python "%~dp0tool\run.py" doctor
python "%~dp0tool\run.py" setup && python "%~dp0tool\run.py" build
echo.
pause
