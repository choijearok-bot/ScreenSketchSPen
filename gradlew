#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "Downloading Gradle wrapper..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL "https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar" -o "$JAR" || exit 1
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$JAR" "https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar" || exit 1
  else
    echo "curl or wget is required to download the Gradle wrapper jar." >&2
    exit 1
  fi
fi
exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
