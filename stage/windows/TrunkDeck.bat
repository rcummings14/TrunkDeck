@echo off
rem Starts TrunkDeck (normally you use the Start Menu or Desktop icon instead).
set "HERE=%~dp0"
if exist "%HERE%runtime\bin\javaw.exe" (
  start "" "%HERE%runtime\bin\javaw.exe" --enable-native-access=ALL-UNNAMED -XX:-OmitStackTraceInFastThrow -Dtrunkdeck.home="%HERE%." -Xmx768m -jar "%HERE%app\trunkdeck.jar" %*
) else (
  echo TrunkDeck is not installed yet. Run Install.bat first.
  pause
)
