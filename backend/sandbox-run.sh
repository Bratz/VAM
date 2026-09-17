#!/bin/bash
# ponytail: env clearing + ulimits, not Docker-in-Docker. Wraps a command that must not see this
# process's environment (DB_PASSWORD, JIRA_API_TOKEN, ANTHROPIC_API_KEY): GeneratedTransformRunner
# running an agent-generated, unreviewed transform against real uploaded data.
#
# Originally also used `unshare --user --net --map-root-user` for network isolation on top of
# this. Confirmed live on the real OCI VM: unprivileged unshare fails outright ("Operation not
# permitted") under this container's default seccomp/AppArmor profile — this isn't the documented
# fallback anymore, it's the actual shipped behavior, since the primary approach never worked here
# at all. Ceiling: no network or filesystem/disk-IO isolation — only env-clearing + resource caps.
# Upgrade path: a dedicated unprivileged OS user via a narrowly-scoped sudoers entry, or loosen
# the container's seccomp profile specifically for CLONE_NEWUSER/CLONE_NEWNET if that's ever
# worth the tradeoff. See tasks/file-ingest-pipeline-design.md's sandboxing section.
#
# Usage: sandbox-run.sh <command...>
set -euo pipefail

# Confirmed live, twice: `ulimit -v` alone isn't the fix, raising it just delayed the same
# failure. The wrapped command IS a JVM (`mvn`), and with no container-level memory limit set on
# this service, HotSpot's own ergonomics auto-detect the HOST's full memory (23GiB on the real VM)
# and default MaxHeapSize to ~25% of that (~5.75GiB) -- an oversized reservation attempt no
# `ulimit -v` ceiling can reasonably accommodate, since raising the ceiling to fit it defeats the
# point of having one. The actual fix is bounding what the JVM tries to reserve in the first
# place via MAVEN_OPTS (this command's own JVM -- exec-maven-plugin's "java" goal runs the
# generated transform inside Maven's own process, not a fork, so one MAVEN_OPTS covers both).
# `ulimit -v` stays too, as a coarse outer backstop, now sized to comfortably fit the explicit
# heap below rather than trying to guess at whatever HotSpot's ergonomics would otherwise pick.
exec env -i \
  JAVA_HOME="${JAVA_HOME:-}" PATH=/usr/bin:/bin HOME=/tmp \
  MAVEN_OPTS="${SANDBOX_JAVA_OPTS:--Xmx768m -XX:MaxMetaspaceSize=256m -XX:CompressedClassSpaceSize=64m}" \
  bash -c "ulimit -v ${SANDBOX_MEM_KB:-3000000} -t ${SANDBOX_CPU_SECS:-110}; $*"
