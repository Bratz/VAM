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

exec env -i \
  JAVA_HOME="${JAVA_HOME:-}" PATH=/usr/bin:/bin HOME=/tmp \
  bash -c "ulimit -v ${SANDBOX_MEM_KB:-1500000} -t ${SANDBOX_CPU_SECS:-110}; $*"
