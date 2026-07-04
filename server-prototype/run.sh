#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

if [ ! -d "out" ]; then
  echo "Not built yet. Running build first..."
  ./build.sh
fi

INSTANCES_DIR="${1:-$HOME/mojo-servers}"

echo "Starting MojoLauncher Server Manager..."
echo "Instances directory: $INSTANCES_DIR"
echo ""

java -cp out com.mojolauncher.server.Main "$INSTANCES_DIR"
