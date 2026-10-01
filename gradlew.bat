@ECHO OFF
SET DIR=%~dp0
SET JAR=%DIR%gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST "%JAR%" (
  ECHO Downloading Gradle wrapper...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar' -OutFile '%JAR%'"
  IF ERRORLEVEL 1 EXIT /B 1
)
java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
