#!/usr/bin/env bash
# Repetition gate for lifecycle, reconnect and voice tests (ANDROID-TEST-01).
#
# Runs the named JVM test classes N times and reports how many iterations had
# a failure. A test that fails on some iterations is a flake or a production
# race, never "noise": record the count and investigate before merging.
#
#   scripts/run-flake-gate.sh <TestClass>[,<TestClass>...] [N=30]
#   scripts/run-flake-gate.sh HomeClientPairingTest
#   scripts/run-flake-gate.sh OkHttpRelaySessionClientTest,AndroidRecoveryControllerTest 30
#
# A class name without a package is resolved in com.achappell.hermesrelay.
# Exit status is 0 only when every iteration passes with at least one test run.
# Per-iteration Gradle logs are kept under app/build/flake-gate/.

set -euo pipefail

usage() {
  echo "usage: $0 <TestClass>[,<TestClass>...] [N=30]" >&2
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
classes="$1"
iterations="${2:-30}"
[[ "$iterations" =~ ^[1-9][0-9]*$ ]] || usage

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

package="com.achappell.hermesrelay"
filters=()
IFS=',' read -r -a names <<<"$classes"
for name in "${names[@]}"; do
  [[ -n "$name" ]] || usage
  [[ "$name" == *.* ]] || name="$package.$name"
  filters+=(--tests "$name")
done

results_dir="app/build/test-results/testDebugUnitTest"
log_dir="app/build/flake-gate"
mkdir -p "$log_dir"

failed_iterations=0
# One line per failing suite per failing iteration (bash 3.2 on macOS has no
# associative arrays).
failed_suites=""

for ((i = 1; i <= iterations; i++)); do
  log="$log_dir/iteration-$i.log"
  # cleanTestDebugUnitTest discards the previous result so a skipped
  # (up-to-date) task can never pass an iteration; compilation stays cached.
  status=0
  ./gradlew cleanTestDebugUnitTest testDebugUnitTest "${filters[@]}" \
    --console=plain -q >"$log" 2>&1 || status=$?

  tests_run=0
  iteration_failed=0
  for xml in "$results_dir"/TEST-*.xml; do
    [[ -e "$xml" ]] || continue
    header="$(grep -m1 -o '<testsuite [^>]*>' "$xml")"
    suite="$(sed -n 's/.* name="\([^"]*\)".*/\1/p' <<<"$header")"
    count="$(sed -n 's/.* tests="\([0-9]*\)".*/\1/p' <<<"$header")"
    bad="$(sed -n 's/.* failures="\([0-9]*\)".*/\1/p' <<<"$header")"
    errs="$(sed -n 's/.* errors="\([0-9]*\)".*/\1/p' <<<"$header")"
    tests_run=$((tests_run + count))
    if ((bad + errs > 0)); then
      iteration_failed=1
      failed_suites+="$suite"$'\n'
    fi
  done

  if ((status != 0 || tests_run == 0)); then
    iteration_failed=1
  fi
  if ((iteration_failed)); then
    failed_iterations=$((failed_iterations + 1))
    echo "iteration $i/$iterations: FAIL (gradle status $status, $tests_run tests; log $log)"
  else
    echo "iteration $i/$iterations: ok ($tests_run tests)"
  fi
done

echo
echo "classes: $classes"
passed=$((iterations - failed_iterations))
if ((failed_iterations == 0)); then
  echo "$iterations consecutive runs, 0 failures"
else
  echo "$iterations runs, $failed_iterations failed ($passed passed)"
  printf '%s' "$failed_suites" | sort | uniq -c | sed -n 's/^ *\([0-9]*\) \(.*\)$/  \2: failed in \1 iteration(s)/p'
  exit 1
fi
