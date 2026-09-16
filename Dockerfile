# ============================================================================
# Root Dockerfile — builds the BACKEND from the repository root.
#
# Exists for deploy platforms (Render, Railway, …) that clone the repo and
# look for ./Dockerfile with the repo root as build context. It is the same
# multi-stage build as backend/Dockerfile, with paths prefixed. To build the
# frontend instead, point your service at frontend/Dockerfile.
#
# Platform env vars the app needs:
#   SPRING_DATASOURCE_URL       jdbc:postgresql://<host>:5432/<db>
#   SPRING_DATASOURCE_USERNAME  <user>
#   DB_PASSWORD                 <password>
#   PORT                        (injected by most platforms; honored below)
# ============================================================================

# ---- Stage 1: build --------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml .
RUN mvn dependency:go-offline -B
COPY backend/src ./src
RUN mvn clean package -DskipTests -B

# ---- Stage 2: runtime ------------------------------------------------------
# jdk-alpine, not jre-alpine: GeneratedTransformRunner (the file-ingest pipeline's real-data path)
# shells out to `mvn ... exec-maven-plugin ...` to run an already-tested, agent-generated
# transform, which needs javac + the mvn binary — neither exists in a JRE image. See
# tasks/file-ingest-pipeline-design.md's sandboxing section.
FROM eclipse-temurin:21-jdk-alpine

# psql for the optional first-boot seeding (see docker-entrypoint.sh); bash/maven for
# GeneratedTransformRunner above; util-linux for sandbox-run.sh's `unshare` (Alpine's busybox
# unshare applet may not support the flags used — verify on the real VM, see the design doc).
RUN apk add --no-cache postgresql-client bash maven util-linux && \
    addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Demo-data dump baked into the image so the container can seed a fresh
# platform database itself (set SEED_ON_START=true; idempotent).
COPY database/dump/vam_db_full.sql.gz /app/seed/vam_db_full.sql.gz
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
COPY backend/sandbox-run.sh /app/sandbox-run.sh
RUN chown -R appuser:appgroup /app && chmod +x /app/docker-entrypoint.sh /app/sandbox-run.sh
USER appuser

# App default is 8053; most PaaS inject PORT and route to it — honored in the
# entrypoint below.
EXPOSE 8053

# Container-aware heap (75% of the container's RAM limit). Honest note: this
# service's Spring context is large — plan ~1GB RAM; a 512MB free tier will
# OOM at startup or thrash.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication"

ENTRYPOINT ["/app/docker-entrypoint.sh"]
