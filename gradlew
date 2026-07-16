#!/bin/sh

APP_NAME="Gradle"
GRADLE_VERSION="8.11"

DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"

# Determine the script's directory
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`/"$link"
    fi
done
APP_HOME=`dirname "$PRG"`
APP_HOME=`cd "$APP_HOME" && pwd`

CLASSPATH="$APP_HOME/$WRAPPER_JAR"

# Execute Gradle
exec java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
