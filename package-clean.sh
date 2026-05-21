#!/usr/bin/env bash
# package-clean.sh — create a clean source ZIP for distribution.
# Usage: ./package-clean.sh [output-name]
# Output: custoking-ims-clean.zip (or the name you supply as $1)

set -euo pipefail

OUTPUT="${1:-custoking-ims-clean.zip}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Building clean source package: $OUTPUT"
echo "    Source root: $SCRIPT_DIR"

cd "$SCRIPT_DIR"

# Remove previous ZIP if it exists.
rm -f "$OUTPUT"

zip -r "$OUTPUT" . \
  --exclude ".git" \
  --exclude ".git/*" \
  --exclude "*/.git" \
  --exclude "*/.git/*" \
  --exclude ".git/**" \
  --exclude "**/.git/**" \
  --exclude "node_modules" \
  --exclude "node_modules/*" \
  --exclude "*/node_modules/*" \
  --exclude "**/node_modules/**" \
  --exclude "dist" \
  --exclude "dist/*" \
  --exclude "*/dist/*" \
  --exclude "**/dist/**" \
  --exclude "frontend/dist" \
  --exclude "frontend/dist/*" \
  --exclude "target" \
  --exclude "target/*" \
  --exclude "*/target/*" \
  --exclude "**/target/**" \
  --exclude "backend/target" \
  --exclude "backend/target/*" \
  --exclude "build/*" \
  --exclude ".idea" \
  --exclude ".idea/*" \
  --exclude "*/.idea/*" \
  --exclude "**/.idea/**" \
  --exclude ".vscode" \
  --exclude ".vscode/*" \
  --exclude "*/.vscode/*" \
  --exclude "**/.vscode/**" \
  --exclude ".env" \
  --exclude "*/.env" \
  --exclude ".env.local" \
  --exclude ".env.development" \
  --exclude ".env.test" \
  --exclude ".env.production" \
  --exclude "*/.env.local" \
  --exclude "*/.env.development" \
  --exclude "*/.env.test" \
  --exclude "*/.env.production" \
  --exclude "logs" \
  --exclude "logs/*" \
  --exclude "*/logs/*" \
  --exclude "**/logs/**" \
  --exclude "tmp" \
  --exclude "tmp/*" \
  --exclude "*/tmp/*" \
  --exclude "**/tmp/**" \
  --exclude "uploads" \
  --exclude "uploads/*" \
  --exclude "*/uploads/*" \
  --exclude "**/uploads/**" \
  --exclude "*.log" \
  --exclude "*.tmp" \
  --exclude "custoking-ims-clean.zip" \
  --exclude "coverage/*" \
  --exclude "playwright-report/*" \
  --exclude "test-results/*"

SIZE=$(du -sh "$OUTPUT" | cut -f1)
echo "==> Done. Package: $OUTPUT ($SIZE)"
echo ""
echo "    Included:"
echo "      - Source code (backend/src, frontend/src)"
echo "      - Flyway migrations"
echo "      - Dockerfiles + docker-compose.yml"
echo "      - GitHub Actions (.github/)"
echo "      - Cloud Build (cloudbuild.yaml)"
echo "      - README files"
echo "      - .env.example"
echo ""
echo "    Excluded:"
echo "      - .git/, node_modules/, dist/, target/"
echo "      - .env, .env.*, logs/, tmp/, uploads/"
echo "      - IDE files (.idea/, .vscode/)"
