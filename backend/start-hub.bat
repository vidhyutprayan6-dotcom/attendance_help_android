@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo ========================================
echo  Attendance Help Hub (Bongsagi)
echo  Stopping any old server on port 8765...
echo ========================================

for /L %%i in (1,1,3) do (
  for /f "tokens=5" %%a in ('netstat -ano 2^>nul ^| findstr ":8765" ^| findstr "LISTENING"') do (
    echo Killing PID %%a on port 8765
    taskkill /F /PID %%a >nul 2>&1
  )
  ping -n 2 127.0.0.1 >nul
)

netstat -ano 2>nul | findstr ":8765" | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo.
  echo ERROR: Port 8765 is still in use.
  echo.
  netstat -ano | findstr ":8765"
  echo.
  echo Copy the PID from the last column, then run:
  echo   taskkill /F /PID ^<PID^>
  echo Then run start-hub.bat again.
  exit /b 1
)

echo Port 8765 is free.
echo.
echo Installing dependencies (first run only)...
call npm install
if errorlevel 1 (
  echo npm install failed.
  exit /b 1
)

echo.
echo TURN config (OpenRelay - development testing only):
set "TURN_URLS=turn:openrelay.metered.ca:80,turn:openrelay.metered.ca:443,turn:openrelay.metered.ca:443?transport=tcp"
set "TURN_USER=openrelayproject"
set "TURN_CRED=openrelayproject"
echo   TURN_URLS=%TURN_URLS%
echo   TURN_USER=%TURN_USER%
echo.

echo Starting hub server...
echo.
node server.js
endlocal
