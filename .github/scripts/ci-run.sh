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
    # which for a long build is usually unrelated output. The "-A 6" matters:
    # CMake prints the reason on the lines after "CMake Error", and every
    # feature probe in this build prints the word "not found" as routine noise.
    highlights="$(grep -a -A 6 -E 'CMake Error' "$log" 2>/dev/null | head -300)"
    if [ -z "$highlights" ]; then
        highlights="$(grep -a -E 'fatal error:|error: |undefined reference|Error:|Traceback|CMakeFiles/.*\.o' "$log" 2>/dev/null | head -40)"
    fi
    if [ -z "$highlights" ]; then
        highlights="$(tail -n 40 "$log" 2>/dev/null)"
    fi
    tail_lines="$(tail -n 8 "$log" 2>/dev/null)"

    message="$(printf '%s\n%s\n' "$highlights" "$tail_lines" \
        | sed -e 's/%/%25/g' -e 's/\r//g' \
        | awk 'NF { printf "%s%%0A", $0 }')"
    printf '::error::%s\n' "${message:0:8000}"
fi

exit "$status"
