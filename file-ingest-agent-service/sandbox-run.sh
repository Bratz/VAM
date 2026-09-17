#!/bin/bash
# ponytail: env clearing + ulimits, not Docker-in-Docker. Clears the environment so the coding
# agent's run_command can't read this process's DB_PASSWORD/JIRA_API_TOKEN/ANTHROPIC_API_KEY.
#
# Originally also used `unshare --user --map-root-user` for identity/mount isolation on top of
# this (no --net here even originally — the agent legitimately needs network for mvn/dependency
# resolution). Confirmed live on the real OCI VM: unprivileged unshare fails outright ("Operation
# not permitted") under this container's default seccomp/AppArmor profile — the coding agent
# burned its entire turn budget on nothing but failing run_command calls before this was caught.
# Not the documented fallback anymore, it's the actual shipped behavior, since the primary
# approach never worked here at all. Ceiling: no filesystem/disk-IO isolation — only env-clearing
# + resource caps. See tasks/file-ingest-pipeline-design.md's sandboxing section.
#
# Usage: sandbox-run.sh <command...>
set -euo pipefail

# Confirmed live (backend's copy of this same script): raising `ulimit -v` alone doesn't fix
# this, it just delays the same failure. The wrapped command is routinely a JVM (`mvn`), and with
# no container-level memory limit set on this service either, HotSpot's own ergonomics auto-
# detect the HOST's full memory and default MaxHeapSize to ~25% of it -- on the real VM (23GiB)
# that's an oversized reservation attempt no reasonable `ulimit -v` ceiling accommodates. MAVEN_OPTS
# bounds what the JVM actually tries to reserve; `ulimit -v` stays as a coarse outer backstop,
# now sized to comfortably fit the explicit heap below instead of guessing at HotSpot's ergonomics.
exec env -i \
  JAVA_HOME="${JAVA_HOME:-}" PATH=/usr/bin:/bin HOME=/tmp \
  MAVEN_OPTS="${SANDBOX_JAVA_OPTS:--Xmx768m -XX:MaxMetaspaceSize=256m -XX:CompressedClassSpaceSize=64m}" \
  bash -c "ulimit -v ${SANDBOX_MEM_KB:-3000000} -t ${SANDBOX_CPU_SECS:-110}; $*"
