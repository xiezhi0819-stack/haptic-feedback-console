@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
if exist ".tools\jdk-21\bin\javac.exe" set "JAVA_HOME=%CD%\.tools\jdk-21"
if exist ".tools\maven\bin\mvn.cmd" set "PATH=%CD%\.tools\maven\bin;%PATH%"
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" set "PATH=%JAVA_HOME%\bin;%PATH%"
)
where javac >nul 2>&1
if errorlevel 1 goto needjava
javac -version 2>&1 | findstr /r /c:"javac 21\." /c:"javac 21$" >nul
if errorlevel 1 goto needjava
where mvn >nul 2>&1
if errorlevel 1 goto needmaven
if not exist ".venv\Scripts\python.exe" (
  python -c "import sys; sys.exit(sys.version_info < (3,10))"
  if errorlevel 1 goto needpython
  python -m venv .venv
  if errorlevel 1 goto failed
)
".venv\Scripts\python.exe" -c "import bleak, serial" >nul 2>&1
if errorlevel 1 (
  ".venv\Scripts\python.exe" -m pip install -r backend\requirements.txt
  if errorlevel 1 goto failed
)
call mvn -f frontend\pom.xml javafx:run
if errorlevel 1 goto failed
exit /b 0

:needjava
echo 需要 JDK 21。请安装后将 JAVA_HOME 指向 JDK 21，并重新打开窗口。
goto failed
:needmaven
echo 找不到 Maven。请把 Maven 的 bin 目录加入 PATH。
goto failed
:needpython
echo 需要 Python 3.10 或以上，并将 python 加入 PATH。
:failed
echo 启动失败，请查看上方输出。首次安装依赖需要联网。
pause
exit /b 1
