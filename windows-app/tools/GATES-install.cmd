@echo off
setlocal EnableExtensions
title GATES installer

net session >nul 2>&1
if not "%errorlevel%"=="0" (
  echo Requesting administrator privileges...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

set "URL=https://cloak.yokhan.xyz/downloads/GATES-latest-windows.msi"
set "MSI=%TEMP%\GATES-latest-windows.msi"

echo Downloading GATES...
del /f /q "%MSI%" >nul 2>&1
curl.exe -L --fail --retry 3 --retry-delay 2 -o "%MSI%" "%URL%"
if errorlevel 1 (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%MSI%' } catch { exit 1 }"
)
if not exist "%MSI%" goto :download_failed
for %%A in ("%MSI%") do if %%~zA LSS 100000000 goto :download_failed

echo Stopping GATES...
taskkill /IM GATES.exe >nul 2>&1
timeout /t 2 /nobreak >nul
taskkill /F /IM GATES.exe >nul 2>&1

sc.exe query GatesVpn >nul 2>&1
if not errorlevel 1 (
  sc.exe stop GatesVpn >nul 2>&1
  for /l %%i in (1,1,30) do (
    sc.exe query GatesVpn | findstr /I "STOPPED" >nul && goto :service_stopped
    timeout /t 1 /nobreak >nul
  )
  taskkill /F /T /IM Gates.Service.exe >nul 2>&1
)
:service_stopped
powershell -NoProfile -ExecutionPolicy Bypass -Command "$root='C:\Program Files\GATES\'; Get-Process xray,sing-box -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($root,[System.StringComparison]::OrdinalIgnoreCase) } | Stop-Process -Force -ErrorAction SilentlyContinue"
timeout /t 2 /nobreak >nul

echo Installing...
msiexec.exe /i "%MSI%" /passive /norestart
set "RC=%errorlevel%"
echo MSI_EXIT=%RC%
if "%RC%"=="0" goto :start_service
if "%RC%"=="3010" goto :start_service
if "%RC%"=="1641" goto :start_service
if "%RC%"=="1638" goto :start_service
echo.
echo Install failed with code %RC%.
pause
exit /b %RC%

:start_service
sc.exe start GatesVpn >nul 2>&1
timeout /t 4 /nobreak >nul
for /f "tokens=*" %%V in ('powershell -NoProfile -Command "(Get-Item 'C:\Program Files\GATES\GATES.exe').VersionInfo.FileVersion"') do set "VER=%%V"
echo Installed GATES version: %VER%
sc.exe query GatesVpn | findstr /I "RUNNING" >nul
if errorlevel 1 echo Warning: service is not running yet; the app will repair it.
sc.exe query GatesVpn | findstr /I "STATE"
echo Launching GATES...
explorer.exe "C:\Program Files\GATES\GATES.exe"
echo.
echo GATES updated successfully.
timeout /t 5 >nul
exit /b 0

:download_failed
echo.
echo Could not download the GATES installer. Check the internet connection.
pause
exit /b 1
