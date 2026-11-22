#!/bin/sh
# CyberDrill — Gradle wrapper launcher (Gradle 8.9).
# Resolves the wrapper jar sitting next to this script and hands over to
# GradleWrapperMain, which downloads/uses the distribution pinned in
# gradle/wrapper/gradle-wrapper.properties.
APP_HOME=$(cd "$(dirname "$0")" || exit 1; pwd -P)
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi
exec "$JAVACMD" \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
