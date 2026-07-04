#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

SRC_DIR="src"
TEST_DIR="test"
OUT_DIR="out"
TEST_OUT_DIR="test-out"

echo "=== MojoLauncher Server Prototype — Build ==="

rm -rf "$OUT_DIR" "$TEST_OUT_DIR"
mkdir -p "$OUT_DIR" "$TEST_OUT_DIR"

echo "Compiling sources..."
find "$SRC_DIR" -name "*.java" > /tmp/mojo_src_files.txt
javac -d "$OUT_DIR" @/tmp/mojo_src_files.txt
echo "Sources compiled -> $OUT_DIR/"

echo "Compiling tests..."
find "$TEST_DIR" -name "*.java" > /tmp/mojo_test_files.txt
javac -cp "$OUT_DIR" -d "$TEST_OUT_DIR" @/tmp/mojo_test_files.txt
echo "Tests compiled -> $TEST_OUT_DIR/"

echo ""
echo "Build successful. Run with: ./run.sh"
echo "Run tests with: ./test.sh"
