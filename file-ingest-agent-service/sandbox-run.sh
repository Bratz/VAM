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

# Confirmed live (backend's copy of this same script, same default): `ulimit -v` caps VIRTUAL
# address space, not physical/resident memory -- but HotSpot reserves ~1GiB of virtual space by
# default just for CompressedClassSpaceSize before running any code. The wrapped command here is
# routinely a JVM (`mvn`), so a 1.5GB `-v` cap can fail a normal JVM's own startup with "Could not
# allocate compressed class space: 1073741824 bytes" before it ever reaches the generated code.
# Raised to match, well above that baseline reservation + Maven's own overhead.
exec env -i \
  JAVA_HOME="${JAVA_HOME:-}" PATH=/usr/bin:/bin HOME=/tmp \
  bash -c "ulimit -v ${SANDBOX_MEM_KB:-3000000} -t ${SANDBOX_CPU_SECS:-110}; $*"
