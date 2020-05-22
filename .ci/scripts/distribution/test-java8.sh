#!/bin/sh -eux


export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:MaxRAMFraction=$((LIMITS_CPU))"

mvn -v

mvn -o -B -T$LIMITS_CPU -s ${MAVEN_SETTINGS_XML} verify -pl clients/java -DtestMavenId=3 -Dsurefire.rerunFailingTestsCount=5 | tee test-java8.txt
#status=${PIPESTATUS[0]}

if grep -q "\[WARNING\] Flakes:" test-java8.txt; then
    awk '/^\[WARNING\] Flakes:.*$/{flag=1}/^\[ERROR\] Tests run:.*Flakes: [0-9]*$/{print;flag=0}flag' test-java8.txt > flaky-tests-log.txt

  grep "\[ERROR\]   Run 1: " flaky-tests-log.txt | awk '{print $4}' >> ./target/FlakyTests.txt

  echo ERROR: Flaky Tests detected>&2
  rm test-java8.txt
  rm flaky-tests-log.txt

  exit 1
fi

rm test-java8.txt
#exit $status
