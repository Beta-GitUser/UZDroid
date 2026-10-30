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

    # Report the first handful of problems one annotation each: a single blob
    # is unreadable, and "note:" lines would drown out the actual errors.
    # ': error: ' cannot match 'note: ' the way a bare 'e: ' does.
    errlist="$(mktemp)"
    grep -a -n -E ': (fatal )?error: |^ninja: error|undefined reference to|FAILURE: Build failed|Execution failed for task' "$log" 2>/dev/null \
        | grep -v -F 'note: ' > "$errlist" || true

    reported=0
    while IFS=: read -r lineno rest; do
        [ -z "${lineno:-}" ] && continue
        [ "$reported" -ge 8 ] && break
        snippet="$(sed -n "${lineno},$((lineno + 4))p" "$log" 2>/dev/null)"
        msg="$(printf '%s' "$snippet" | sed -e 's/%/%25/g' -e 's/\r//g' | awk 'NF { printf "%s%%0A", $0 }')"
        printf '::error::%s\n' "${msg:0:1800}"
        reported=$((reported + 1))
    done < "$errlist"

    if [ "$reported" -eq 0 ]; then
        tail_lines="$(tail -n 15 "$log" 2>/dev/null)" || true
        msg="$(printf '%s' "$tail_lines" | sed -e 's/%/%25/g' -e 's/\r//g' | awk 'NF { printf "%s%%0A", $0 }')"
        printf '::error::no error lines matched, last output: %s\n' "${msg:0:3000}"
    fi
    rm -f "$errlist"
fi

exit "$status"
