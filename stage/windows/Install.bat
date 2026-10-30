@echo off
rem Double-click to install TrunkDeck for your Windows user (no admin needed).
title TrunkDeck installer
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
if errorlevel 1 (
  echo.
  echo Installation did not finish. See the message above.
)
pause
