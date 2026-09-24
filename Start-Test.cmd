@echo off
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME if exist "D:\Codex\toolchains\jdk-17.0.20.1+1\bin\java.exe" set "JAVA_HOME=D:\Codex\toolchains\jdk-17.0.20.1+1"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JDK not found: %JAVA_HOME%
    pause
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build.ps1" :fabric-1.20.1:runClient
if errorlevel 1 (
    echo Test client failed to start. See the error above.
    pause
    exit /b 1
)
endlocal
