# ============================================================================
# defect-fix-service — the automated defect-fix pipeline
# (see tasks/defect-autofix-pipeline-design.md).
#
# Runtime image is heavier than a typical microservice: the coding agent's
# test gate runs the actual project build tools (mvn test for backend
# defects, npm run type-check/lint for frontend ones) INSIDE this container,
# against a git worktree it checks out itself — so it needs a full JDK
# (not just a JRE), Maven, Node/npm, and git, not just the service's own jar.
#
# Env vars this service needs (see application.yml for the full list):
#   JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN, JIRA_PROJECT_KEY
#   GITHUB_OWNER, GITHUB_REPO, GITHUB_TOKEN, GITHUB_WEBHOOK_SECRET
#   ANTHROPIC_API_KEY, ANTHROPIC_MODEL
#   AGENT_WORKSPACE_DIR (should be a mounted volume — holds the repo clone
#     and per-ticket worktrees, including ones left behind for human
#     inspection after an escalation)
# ============================================================================

# ---- Stage 1: build ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY defect-fix-service/pom.xml .
RUN mvn dependency:go-offline -B
COPY defect-fix-service/src ./src
RUN mvn clean package -DskipTests -B

# ---- Stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine

RUN apk add --no-cache git bash maven nodejs npm && \
    addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /data/defect-fix-workspace && chown -R appuser:appgroup /app /data/defect-fix-workspace

# The coding agent needs a git identity to create commits.
RUN git config --system user.email "defect-fix-bot@vam-portal.local" && \
    git config --system user.name "Defect Fix Bot"

USER appuser
EXPOSE 8090

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
