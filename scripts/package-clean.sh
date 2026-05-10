#!/usr/bin/env bash
# Creates a clean ZIP of the project source, excluding build outputs and secrets.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="$ROOT_DIR/custoking-ims-clean.zip"
TEMP_DIR="$(mktemp -d)"

echo "Packaging from: $ROOT_DIR"
echo "Output:         $OUTPUT"

# Cleanup on exit
trap 'rm -rf "$TEMP_DIR"' EXIT

# Exclude patterns
rsync -a --progress \
  --exclude='.git' \
  --exclude='node_modules' \
  --exclude='frontend/node_modules' \
  --exclude='frontend/dist' \
  --exclude='backend/target' \
  --exclude='*.log' \
  --exclude='*.tmp' \
  --exclude='.env' \
  --exclude='.env.*' \
  --exclude='uploads' \
  --exclude='custoking-ims-clean.zip' \
  --exclude='.DS_Store' \
  --exclude='.idea' \
  --exclude='coverage' \
  --exclude='playwright-report' \
  --exclude='test-results' \
  "$ROOT_DIR/" "$TEMP_DIR/custoking-ims/"

cd "$TEMP_DIR"
zip -r "$OUTPUT" custoking-ims/

echo ""
echo "Done! Clean ZIP: $OUTPUT"
du -sh "$OUTPUT"
