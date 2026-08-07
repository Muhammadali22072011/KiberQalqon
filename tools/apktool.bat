@echo off
setlocal
chcp 65001 >nul
pushd "%~dp0"

set "JAVA_EXE="

rem 1) Local tools\jdk\ has priority (unpacked by decode_apk.py).
rem    java.exe alone is not enough - a half-extracted JDK has no jvm.dll and
rem    dies with "missing `server' JVM". Require the VM library too.
if exist "%~dp0jdk\bin\java.exe" (
  if exist "%~dp0jdk\bin\server\jvm.dll" set "JAVA_EXE=%~dp0jdk\bin\java.exe"
  if exist "%~dp0jdk\lib\server\jvm.dll" set "JAVA_EXE=%~dp0jdk\bin\java.exe"
)

rem 2) JAVA_HOME, if not found yet
if not defined JAVA_EXE (
  if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

rem 3) java in PATH - separate if, otherwise && and if-logic mix and break
if not defined JAVA_EXE (
  where java >nul 2>&1
  if not errorlevel 1 set "JAVA_EXE=java"
)

if not defined JAVA_EXE goto nojava

if "%JAVA_EXE%"=="java" (
  java -jar -Xmx1024M -Dfile.encoding=UTF8 -Djdk.util.zip.disableZip64ExtraFieldValidation=true "%~dp0apktool_2.12.1.jar" %*
) else (
  "%JAVA_EXE%" -jar -Xmx1024M -Dfile.encoding=UTF8 -Djdk.util.zip.disableZip64ExtraFieldValidation=true "%~dp0apktool_2.12.1.jar" %*
)
goto end

:nojava
echo Java ne nayden. Raspakuy jdk21.zip v podpapku "jdk" zdes ili ustanovi JDK v PATH.
popd
exit /b 1

:end
set ERR=%ERRORLEVEL%
popd
exit /b %ERR%
