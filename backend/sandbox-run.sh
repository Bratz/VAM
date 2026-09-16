#!/bin/bash
# ponytail: unprivileged unshare + env clearing + ulimits, not Docker-in-Docker — see
# tasks/file-ingest-pipeline-design.md's sandboxing section. Ceiling: no filesystem/disk-IO
# isolation. Wraps a command that must not see this process's environment (DB_PASSWORD,
# JIRA_API_TOKEN, ANTHROPIC_API_KEY) or reach the network: GeneratedTransformRunner running an
# agent-generated, unreviewed transform against real uploaded data.
#
# Usage: sandbox-run.sh <command...>
set -euo pipefail

exec unshare --user --net --map-root-user -- env -i \
  JAVA_HOME="${JAVA_HOME:-}" PATH=/usr/bin:/bin HOME=/tmp \
  bash -c "ulimit -v ${SANDBOX_MEM_KB:-1500000} -t ${SANDBOX_CPU_SECS:-110}; $*"
