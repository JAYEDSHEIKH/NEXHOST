#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

if [ ! -d "out" ] || [ ! -d "test-out" ] || [ ! -f "fake-server.jar" ]; then
  echo "Building first..."
  ./build.sh
fi

PASS=0
FAIL=0

run_test() {
  local class="$1"
  echo ""
  echo "--- Running $class ---"
  if java -ea -cp "out:test-out" "$class"; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
  fi
}

run_test "com.mojolauncher.server.ServerInstanceTest"
run_test "com.mojolauncher.server.BackupManagerTest"
run_test "com.mojolauncher.server.ServerLifecycleTest"

echo ""
echo "======================================="
echo "Test suites passed: $PASS  failed: $FAIL"
echo "======================================="

if [ $FAIL -gt 0 ]; then exit 1; fi
