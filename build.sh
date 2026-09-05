#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
(cd frontend && npm ci --no-audit --no-fund && npm run build)
mkdir -p src/main/resources/static
cp -R frontend/dist/. src/main/resources/static/
mvn -B package
