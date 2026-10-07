@echo off
setlocal
cd /d "%~dp0"
rem Optional machine-local JDK selection; ignored by Git and scoped to this launcher.
if not defined JAVA_HOME if exist ".tools\java-home.txt" set /p "JAVA_HOME="<".tools\java-home.txt"
if defined JAVA_HOME (
    if not exist "%JAVA_HOME%\bin\java.exe" goto invalid_java_home
    if not exist "%JAVA_HOME%\bin\javac.exe" goto invalid_java_home
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)
where java >nul 2>nul
if errorlevel 1 (
    echo JDK 17 not found. Set JAVA_HOME, add its bin directory to PATH,
    echo or put the JDK directory on the first line of .tools\java-home.txt.
    pause
    exit /b 1
)
where javac >nul 2>nul
if errorlevel 1 (
    echo Java compiler not found. Install JDK 17, not only a JRE.
    pause
    exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build.ps1" :fabric-1.20.1:runClient %*
if errorlevel 1 (
    echo Test client failed to start. See the error above.
    pause
    exit /b 1
)
endlocal
exit /b 0

:invalid_java_home
echo JAVA_HOME must point to a complete JDK containing bin\java.exe and bin\javac.exe.
echo Check JAVA_HOME or .tools\java-home.txt.
pause
exit /b 1
