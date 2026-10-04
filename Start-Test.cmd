@echo off
setlocal
cd /d "%~dp0"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
where java >nul 2>nul
if errorlevel 1 (
    echo JDK 17 not found. Set JAVA_HOME or add its bin directory to PATH.
    pause
    exit /b 1
)
where javac >nul 2>nul
if errorlevel 1 (
    echo Java compiler not found. Install JDK 17, not only a JRE.
    pause
    exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build.ps1" :fabric-1.20.1:runClient
if errorlevel 1 (
    echo Test client failed to start. See the error above.
    pause
    exit /b 1
)
endlocal
