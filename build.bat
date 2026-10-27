@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "BUILD_DIR=%SCRIPT_DIR%build"

cd /d "%SCRIPT_DIR%src"

echo Compiling...
javac *.java
if errorlevel 1 (
    echo Compilation failed.
    exit /b 1
)

if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
move /Y *.class "%BUILD_DIR%\" >nul

echo Done. Classes are in %BUILD_DIR%