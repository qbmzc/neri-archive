#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
(cd frontend && npm ci --no-audit --no-fund && npm run build)
# 前端产物带内容哈希，必须先清空旧目录，否则历代 bundle 会一起打进 jar。
rm -rf src/main/resources/static
mkdir -p src/main/resources/static
cp -R frontend/dist/. src/main/resources/static/
# clean 是必需的：Maven 不会删除 target/classes 里已被移除的资源，
# 增量构建会把上一代前端 bundle 一起打进 jar。
mvn -B clean package
