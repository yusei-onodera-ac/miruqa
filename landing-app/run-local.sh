#!/usr/bin/env bash
# ローカルで、LPを起動する（H2のメモリDB）。http://127.0.0.1:8899/ を開く。Ctrl-C で終了。
set -euo pipefail
cd "$(dirname "$0")"
mvn -q -DskipTests test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
exec java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" -Dcontact.enabled=true com.miruqa.landing.DevServer "${1:-8899}"
