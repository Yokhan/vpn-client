@echo off
setlocal EnableExtensions
title GATES installer

if not exist "C:\ProgramData\GATES" mkdir "C:\ProgramData\GATES" >nul 2>&1
set "LOG=C:\ProgramData\GATES\installer.log"
call :log "=== GATES installer start ==="

net session >nul 2>&1
if not "%errorlevel%"=="0" (
  echo Requesting administrator privileges...
  call :log "elevation requested"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

set "URL=https://cloak.yokhan.xyz/downloads/GATES-latest-windows.msi"
set "MSI=%TEMP%\GATES-latest-windows.msi"

echo Downloading GATES...
call :log "download %URL%"
del /f /q "%MSI%" >nul 2>&1
curl.exe -L --fail --retry 3 --retry-delay 2 -o "%MSI%" "%URL%"
if errorlevel 1 (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%MSI%' } catch { exit 1 }"
)
if not exist "%MSI%" goto :download_failed
for %%A in ("%MSI%") do if %%~zA LSS 100000000 goto :download_failed

echo Stopping GATES...
call :log "stop application and service"
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
call :log "msiexec /i"
msiexec.exe /i "%MSI%" /passive /norestart
set "RC=%errorlevel%"
call :log "msiexec_i_exit=%RC%"
if "%RC%"=="0" goto :repair
if "%RC%"=="3010" goto :repair
if "%RC%"=="1641" goto :repair
if "%RC%"=="1638" goto :repair
echo.
echo Install failed with code %RC%. See %LOG%
call :log "install failed rc=%RC%"
pause
exit /b %RC%

:repair
echo Verifying installed files...
call :log "msiexec /fa repair"
msiexec.exe /fa "%MSI%" /qn /norestart
set "REPAIR_RC=%errorlevel%"
call :log "msiexec_fa_exit=%REPAIR_RC%"
call :verify
if not "%MISSING%"=="0" (
  echo.
  echo Repair did not restore all GATES files. See %LOG%
  call :log "verification failed missing=%MISSING%"
  if "%RC%"=="3010" echo Windows requires a reboot; reboot and run this installer again.
  if "%REPAIR_RC%"=="3010" echo Windows requires a reboot; reboot and run this installer again.
  pause
  exit /b 1
)
call :log "verification ok"

:start_service
sc.exe start GatesVpn >nul 2>&1
for /l %%i in (1,1,30) do (
  sc.exe query GatesVpn | findstr /I "RUNNING" >nul && goto :service_running
  timeout /t 1 /nobreak >nul
)
:service_running
for /f "tokens=*" %%V in ('powershell -NoProfile -Command "(Get-Item 'C:\Program Files\GATES\GATES.exe').VersionInfo.FileVersion"') do set "VER=%%V"
echo Installed GATES version: %VER%
call :log "installed_version=%VER%"
sc.exe query GatesVpn | findstr /I "RUNNING" >nul
if errorlevel 1 (
  echo Warning: service is not running yet; the app will repair it.
  call :log "service_not_running"
)
sc.exe query GatesVpn | findstr /I "STATE"
echo Launching GATES...
call :log "launch"
explorer.exe "C:\Program Files\GATES\GATES.exe"
echo.
echo GATES updated successfully.
call :log "=== GATES installer done ==="
timeout /t 5 >nul
exit /b 0

:verify
set "MISSING=0"
if not exist "C:\Program Files\GATES\runtime\lib\modules" set "MISSING=1"
if not exist "C:\Program Files\GATES\app\resources\gates-service\Gates.Service.jar" set "MISSING=1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$app='C:\Program Files\GATES\app'; $cfg=Join-Path $app 'GATES.cfg'; $missing=0; if(Test-Path -LiteralPath $cfg){ foreach($line in Get-Content -LiteralPath $cfg){ if($line -match '^app.classpath=\$APPDIR\\(.+)$'){ if(-not (Test-Path -LiteralPath (Join-Path $app $matches[1]))){ $missing++ } } } } else { $missing=999 }; Write-Output ('MISSING_FILES=' + $missing); if($missing -ne 0){ exit 1 }"
if errorlevel 1 set "MISSING=1"
exit /b 0

:download_failed
echo.
echo Could not download the GATES installer. Check the internet connection.
call :log "download failed"
pause
exit /b 1

:log
echo [%DATE% %TIME%] %~1>>"%LOG%"
exit /b 0
