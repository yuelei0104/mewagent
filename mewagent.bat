@echo off
setlocal

set "JAR=build\libs\mewagent.jar"

if not exist "%JAR%" (
    echo [mewagent] Jar not found, building...
    call "%~dp0gradlew.bat" shadowJar
    if errorlevel 1 (
        echo [mewagent] Build failed.
        exit /b 1
    )
)

if not exist "%JAR%" (
    echo [mewagent] Jar still missing after build: %JAR%
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [mewagent] Java not found in PATH. Please install JDK 21 or set JAVA_HOME.
    exit /b 1
)

java -jar "%JAR%" %*
endlocal
