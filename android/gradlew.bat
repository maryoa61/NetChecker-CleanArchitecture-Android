@if "%DEBUG%" == "" @hex off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows behavior
@if "%DEBUG%" == "" @echo off
@setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.\

set WRAPPER_JAR=%DIRNAME%gradle\wrapper\gradle-wrapper.jar

@rem Auto-download gradle-wrapper.jar if missing
if not exist "%WRAPPER_JAR%" (
    echo gradle-wrapper.jar not found at %WRAPPER_JAR%
    echo Downloading gradle-wrapper.jar...
    if not exist "%DIRNAME%gradle\wrapper" mkdir "%DIRNAME%gradle\wrapper"
    powershell -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar', '%WRAPPER_JAR%')"
    if not exist "%WRAPPER_JAR%" (
        echo ERROR: Failed to download gradle-wrapper.jar.
        exit /b 1
    )
    echo gradle-wrapper.jar downloaded successfully.
)

@rem Find java.exe
if defined JAVA_HOME (
    set JAVACMD="%JAVA_HOME%\bin\java.exe"
) else (
    set JAVACMD=java.exe
)

"%JAVACMD%" -jar "%WRAPPER_JAR%" %*
