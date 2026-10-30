#!/usr/bin/env bash
#
# Runs a CI step and makes its failure readable without downloading the full
# log: the lines that actually say what went wrong are echoed back as a GitHub
# annotation (::error::) and the tail goes into the job summary. Annotations
# are always reachable, which matters when the log endpoints are not.
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

    # What actually went wrong, from anywhere in the log - not just the tail,
    # which for a long build is usually unrelated output.
    highlights="$(grep -a -i -E \
        'cmake error|fatal error|error:|undefined reference|no such file|not found|cannot find|failed|exception|traceback' \
        "$log" 2>/dev/null \
        | grep -v -i -E 'warning|deprecat|^-W|0 errors' \
        | grep -v '^+ ' \
        | head -40)"
    tail_lines="$(tail -n 12 "$log" 2>/dev/null)"

    message="$(printf '%s\n%s\n' "$highlights" "$tail_lines" \
        | sed -e 's/%/%25/g' -e 's/\r//g' \
        | awk 'NF { printf "%s%%0A", $0 }')"
    printf '::error::%s\n' "${message:0:6000}"
fi

exit "$status"
