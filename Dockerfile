FROM node:22-bookworm-slim AS frontend
WORKDIR /build
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ src/
COPY LICENSE NOTICE ./
COPY --from=frontend /build/dist/ src/main/resources/static/
RUN mvn -B -q package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 archive && useradd --uid 10001 --gid archive --no-create-home archive \
    && mkdir -p /app /data /music && chown archive:archive /app /data /music
WORKDIR /app
COPY --from=backend /build/target/neri-archive-0.1.0.jar app.jar
ENV ARCHIVE_DATA=/data ARCHIVE_MUSIC=/music JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 CMD curl -fsS http://localhost:8080/healthz || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
