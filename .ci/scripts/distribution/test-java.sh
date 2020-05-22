#!/bin/sh -eux


export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:MaxRAMFraction=$((LIMITS_CPU))"

mvn -o -B -fn -T$LIMITS_CPU -s ${MAVEN_SETTINGS_XML} verify -P skip-unstable-ci,parallel-tests -Dzeebe.it.skip -DtestMavenId=1 | tee test.txt

status=${PIPESTATUS[0]}

if [[ $status != 0 ]]; then
  rm test.txt
  exit $status;
fi

if grep -q "There are test failures\." test.txt; then
  rm test.txt
  exit 1
fi
