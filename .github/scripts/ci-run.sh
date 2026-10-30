#!/usr/bin/env bash
#
# Runs a CI step and makes its failure visible without downloading the full
# log: the tail of the output is echoed back as a GitHub annotation
# (::error::) and added to the job summary. Annotations are always reachable,
# which matters when the log endpoints are not.
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Usage:  ci-run.sh <log-file> <command> [args...]

set -uo pipefail

log="$1"
shift

mkdir -p "$(dirname "$log")" 2>/dev/null || true
: > "$log"

echo "== $*"

set +e
"$@" 2>&1 | tee -a "$log"
status="${PIPESTATUS[0]}"
set -e

if [ "$status" -ne 0 ]; then
    {
        echo "### \`$*\` failed (exit $status)"
        echo
        echo '```'
        tail -n 40 "$log"
        echo '```'
    } >> "${GITHUB_STEP_SUMMARY:-/dev/null}" 2>/dev/null || true

    message="$(tail -n 20 "$log" \
        | sed -e 's/%/%25/g' -e 's/\r//g' \
        | awk '{ printf "%s%%0A", $0 }')"
    printf '::error::%s\n' "${message:0:3500}"
fi

exit "$status"
