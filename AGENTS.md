# AGENTS.md

Java 21 / Spring Boot 3.5 + Vue 3 + SQLite app that polls Netease Cloud Music playlists and archives audio. Single Maven module. Code comments, docs, and UI strings are Chinese; commit messages are English.

## Commands

- Full build (frontend -> `static/` -> jar): `sh build.sh` (or `.\build.ps1` on Windows). Do not ship a jar built with bare `mvn package`.
- Backend tests: `mvn test`; one test: `mvn -Dtest=QualityComparatorTest test`.
- Frontend typecheck + build: `cd frontend && npm run build` (`vue-tsc --noEmit` is included; no separate typecheck script).
- Frontend dev: `npm run dev` proxies `/api`, `/login`, `/logout` to :8080. Login/CSRF acceptance must use the packaged :8080 app, not the dev server.
- Run jar: `ADMIN_PASSWORD=<12+ chars> java -jar target/neri-archive-<version>.jar`.
- Opt-in network smoke test: `NERI_LIVE_SMOKE=true mvn -Dtest=LiveGatewayTest test` (skipped by default).
- Docker: `docker compose up -d --build`; `deploy/compose.nas.yaml` pulls the GHCR image. Container/Compose paths are unverified locally (see `VERIFICATION.md`).

## Build gotchas

- `src/main/resources/static/` is generated, gitignored output. The build scripts wipe it before copying `frontend/dist/`; never edit it, and keep `mvn clean` — stale hashed bundles otherwise accumulate in the jar.
- Keep versions in sync in `pom.xml` and `frontend/package.json`. The Dockerfile globs `neri-archive-*.jar`, so don't hardcode jar names.

## Database migrations

- No Flyway/Liquibase. `schema.sql` runs first and is `CREATE TABLE IF NOT EXISTS` only, so it can never add columns to existing DBs. Add columns idempotently in `SchemaMigrations.java`, and create indexes on migrated columns there too — putting such an index in `schema.sql` crashed startup for released 0.1.0 databases (`SchemaMigrationsTest` guards this with `src/test/resources/legacy-schema-0.1.0.sql`).
- Hikari pool size is 1 (SQLite); do not assume concurrent DB access.

## Configuration

- Every `archive.*` property must be explicitly mapped to its env var in `application.yml`: loose `@Value` binding looks up `ARCHIVE_`-prefixed names, which silently ignored `SUPERSEDED_RETENTION_DAYS`.
- New env vars belong in `application.yml`, the README table, `.env.example`, `compose.yaml`, and `deploy/compose.nas.yaml`.

## Invariants (tested; do not simplify away)

- Quality is never faked: no MP3->FLAC transcode, no invented sample specs, unknown tier never replaces known tier, and measured quality (lossless/sample rate/bits/bitrate) — not file size — decides which file is kept.
- Replaced files move to trash (`<music>/.trash`), never deleted unless `SUPERSEDED_RETENTION_DAYS=0`; if the DB commit or the trash move fails, the original file must stay.
- Failed playlist snapshots must not overwrite the previous snapshot; the download queue lives in SQLite and must survive restarts.
- Duplicate cleanup keeps the highest-bitrate/largest copy per group and moves the rest to trash; it never moves a file that changed since the scan, skips groups whose audio belongs to multiple archived songs, and repoints a song to the kept file before trashing its old path.
- `NeteaseCrypto.java` keeps its upstream copyright; project is GPL-3.0-or-later.

## Layout

`api/` REST + Spring Security session/CSRF, `netease/` ported EAPI/WEAPI gateway, `store/` SQLite WAL store, `worker/` scheduler, download pool, quality/duplicate/ffmpeg logic, `frontend/src/App.vue` is the entire SPA (no router or state library). UI colors are CSS variables in `frontend/src/style.css`; dark mode is `[data-theme=dark]` set on `<html>` (inline script in `index.html`, toggle in the topbar).

## Conventions

- Conventional-commit style in English (`feat:`, `fix:`, `ci:`, `release:`); commits go straight to `main`.
- Pushing `main` or a `v*` tag publishes the GHCR image (amd64 only, `.github/workflows/ghcr.yml`).
- `VERIFICATION.md` is a dated verification log (environment, test counts, unverified items) — update it when verifying or releasing.
- `DESIGN-*.md` hold design rationale; their "未实施" status headers are stale, those features shipped.
- Mockito is pinned to `mock-maker-subclass` in `src/test/resources/mockito-extensions`; final classes cannot be mocked. `MediaFilesTest` skips when ffmpeg/ffprobe are absent.
