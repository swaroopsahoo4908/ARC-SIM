@echo off
rem Arc-Sim launcher (Windows). Double-click this file to start the app.
rem A Skylight Rocketry venture -- a Skylight Industries company.

setlocal
cd /d "%~dp0"

set JAR=ArcSim.jar

if not exist "%JAR%" (
    echo ERROR: %JAR% was not found next to this launcher.
    echo Make sure ArcSim.bat stays in the same folder as ArcSim.jar.
    pause
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo ERROR: No Java runtime was found on this PC.
    echo Install a free Java 17 or newer runtime from https://adoptium.net and try again.
    pause
    exit /b 1
)

rem javaw avoids popping up a console window alongside the GUI; fall back to java if javaw is missing.
where javaw >nul 2>nul
if errorlevel 1 (
    start "" java -jar "%JAR%"
) else (
    start "" javaw -jar "%JAR%"
)
