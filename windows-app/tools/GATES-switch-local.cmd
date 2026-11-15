@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "APP_DIR=%%~fI"
set "DEFAULT_MSI=%APP_DIR%\build\compose\binaries\main\msi\GATES-1.9.3.msi"
set "TARGET_MSI=%~1"
if "%TARGET_MSI%"=="" set "TARGET_MSI=%DEFAULT_MSI%"

if not exist "%TARGET_MSI%" (
  echo [ERROR] Local MSI not found: "%TARGET_MSI%"
  echo Build it first with: gradlew.bat :windows-app:packageMsi
  exit /b 1
)

echo ============================================================
echo GATES 1.9.3 local switch helper
echo MSI: "%TARGET_MSI%"
echo Note: Switching briefly stops and restarts GatesVpn service.
echo Preserved state: C:\ProgramData\GATES\Service\active.json
echo                  %%LOCALAPPDATA%%\Yokhan\Gates
echo ============================================================

call "%SCRIPT_DIR%GATES-install.cmd" "%TARGET_MSI%"
exit /b %errorlevel%
