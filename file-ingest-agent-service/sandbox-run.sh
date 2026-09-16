#!/bin/bash
# ponytail: unprivileged env clearing + ulimits, not Docker-in-Docker — see
# tasks/file-ingest-pipeline-design.md's sandboxing section. Ceiling: no filesystem/disk-IO
# isolation. Deliberately NO --net isolation here (unlike backend's copy of this script) — the
# coding agent's run_command legitimately needs network for mvn/dependency resolution. Still
# clears the environment so a generated command can't read this process's DB_PASSWORD/
# JIRA_API_TOKEN/ANTHROPIC_API_KEY.
#
# Usage: sandbox-run.sh <command...>
set -euo pipefail

exec unshare --user --map-root-user -- env -i \
  JAVA_HOME="${JAVA_HOME:-}" PATH=/usr/bin:/bin HOME=/tmp \
  bash -c "ulimit -v ${SANDBOX_MEM_KB:-1500000} -t ${SANDBOX_CPU_SECS:-110}; $*"
