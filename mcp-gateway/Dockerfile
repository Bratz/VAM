# ============================================================================
# mcp-gateway — the OAuth 2.1 + signed-context trust boundary in front of the
# backend's /mcp endpoint (see docs/mcp-architecture.md's target architecture).
#
# Standalone module, same multi-stage shape as the root/backend Dockerfile.
# No seed step: this module never owns schema (jpa.hibernate.ddl-auto: none)
# and reads users/user_account_access straight from the backend's database —
# whatever the backend has already seeded there.
#
# Build context must be the REPO ROOT (matches deploy/oci/docker-compose.yml's
# `context: ../..`), so paths below are prefixed with mcp-gateway/.
#
# Runtime env vars (see mcp-gateway/src/main/resources/application.yml):
#   SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME / DB_PASSWORD
#   GATEWAY_ISSUER              public base URL clients see, e.g. http://<PUBLIC_IP>:9443
#   GATEWAY_DEMO_REDIRECT_URI   must match GATEWAY_ISSUER, e.g. http://<PUBLIC_IP>:9443/authorized
#   BACKEND_MCP_URL             where this gateway proxies tools/call to
# ============================================================================

# ---- Stage 1: build --------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY mcp-gateway/pom.xml .
RUN mvn dependency:go-offline -B
COPY mcp-gateway/src ./src
RUN mvn clean package -DskipTests -B

# ---- Stage 2: runtime ------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 1001 -S appgroup && adduser -u 1001 -S appuser -G appgroup

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 9443

# No unixdomain.tmpdir workaround needed here — that JDK 21 loopback-socket
# bug (see CLAUDE.md) is specific to locked-down Windows sessions (AVD/RDP)
# where AF_UNIX is disabled; a normal Linux container doesn't hit it.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
