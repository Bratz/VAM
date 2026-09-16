# ============================================================================
# file-ingest-agent-service — the file-ingest pipeline's agent worker
# (see tasks/file-ingest-pipeline-design.md's "Revised architecture").
#
# Headless: no customer-facing HTTP, no port mapping — its only interfaces
# are Jira (polls for open file-ingest tickets) and the shared database/
# workspace volume with the main backend, which owns the standard upload/
# staging/processing path.
#
# Runtime image is heavier than a typical microservice for the same reason
# as defect-fix-service's: the coding agent's test gate runs a real `mvn
# test` INSIDE this container, against a git worktree of transform-handlers
# it checks out itself — so it needs a full JDK (not just a JRE), Maven, and
# git+bash, not just the service's own jar.
#
# Env vars this service needs (see application.yml for the full list):
#   SPRING_DATASOURCE_URL/USERNAME, DB_PASSWORD (shared vam_db instance,
#     backend's own schema/tables — this service runs no migrations of its own)
#   JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN, JIRA_PROJECT_KEY (must match
#     backend's own file-ingest Jira config exactly)
#   ANTHROPIC_API_KEY, ANTHROPIC_MODEL
#   INGEST_WORKSPACE_DIR (must be the same mounted volume as backend's own
#     vam.fileingest.workspace-dir — shared source files + transform-handlers)
# ============================================================================

# ---- Stage 1: build ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY file-ingest-agent-service/pom.xml .
RUN mvn dependency:go-offline -B
COPY file-ingest-agent-service/src ./src
RUN mvn clean package -DskipTests -B

# ---- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine

RUN apk add --no-cache git bash maven && \
    addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /data/file-ingest-workspace && chown -R appuser:appgroup /app /data/file-ingest-workspace

USER appuser

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
