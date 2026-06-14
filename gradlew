#!/bin/sh
if [ -n "$JAVA_HOME" ] ; then
    "$JAVA_HOME/bin/java" -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
else
    java -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
