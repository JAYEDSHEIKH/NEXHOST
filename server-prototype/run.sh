#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

if [ ! -d "out" ]; then
  echo "Not built yet. Running build first..."
  ./build.sh
fi

INSTANCES_DIR="${MOJO_SERVERS_DIR:-$HOME/mojo-servers}"

# Parse flags
HTTP_MODE=false
HTTP_PORT=8080

while [[ $# -gt 0 ]]; do
  case "$1" in
    --http)       HTTP_MODE=true;  shift ;;
    --port)       HTTP_PORT="$2";  shift 2 ;;
    --port=*)     HTTP_PORT="${1#--port=}"; shift ;;
    --instances=*)INSTANCES_DIR="${1#--instances=}"; shift ;;
    *)            INSTANCES_DIR="$1"; shift ;;
  esac
done

if $HTTP_MODE; then
  echo "Starting MojoLauncher HTTP API..."
  echo "Instances directory : $INSTANCES_DIR"
  echo "API port            : $HTTP_PORT"
  echo ""
  echo "Quick-start:"
  echo "  List:   curl http://localhost:$HTTP_PORT/api/v1/instances"
  echo "  Create: curl -X POST -H 'Content-Type: application/json' \\"
  echo "          -d '{\"name\":\"test\",\"type\":\"paper\",\"version\":\"latest\",\"acceptEula\":true}' \\"
  echo "          http://localhost:$HTTP_PORT/api/v1/instances"
  echo ""
  java -cp out com.mojolauncher.server.Main "$INSTANCES_DIR" --http --port "$HTTP_PORT"
else
  echo "Starting MojoLauncher Server Manager (interactive CLI)..."
  echo "Instances directory: $INSTANCES_DIR"
  echo "Tip: run with --http to start the REST API instead."
  echo ""
  java -cp out com.mojolauncher.server.Main "$INSTANCES_DIR"
fi
