#!/usr/bin/env bash
#
# Live progress for the Chromium fetch and checkout.
#
# git's own --progress output gets swallowed when the clone is piped, so this
# watches the tree from outside instead: pack size while objects download,
# file count while the working tree is checked out.
#
# Usage:  tools/build-progress.sh [target-dir]
#         Ctrl-C to stop watching. It never touches the clone.

set -uo pipefail

DIR="${1:-/build/chromium/src-105}"

# Chromium at M105: ~1.3 GB packed, ~390k files checked out. Estimates only —
# they set the bar's scale, not the outcome.
EST_PACK=1300000000
EST_FILES=390000

BAR_W=42

human() {
    local b=$1
    if   [ "$b" -ge 1073741824 ]; then awk "BEGIN{printf \"%.2f GB\", $b/1073741824}"
    elif [ "$b" -ge 1048576 ];    then awk "BEGIN{printf \"%.1f MB\", $b/1048576}"
    else                               awk "BEGIN{printf \"%.0f KB\", $b/1024}"
    fi
}

bar() {
    local pct=$1 filled i out=""
    [ "$pct" -gt 100 ] && pct=100
    filled=$(( pct * BAR_W / 100 ))
    for ((i = 0; i < BAR_W; i++)); do
        if [ "$i" -lt "$filled" ]; then out+="█"; else out+="░"; fi
    done
    printf '%s' "$out"
}

start=$(date +%s)
last_bytes=0
last_time=$start

printf '\n  watching %s\n\n' "$DIR"

while true; do
    now=$(date +%s)
    elapsed=$(( now - start ))

    if [ ! -d "$DIR" ]; then
        printf '\r  waiting for clone to start…'
        sleep 2
        continue
    fi

    bytes=$(du -sb "$DIR" 2>/dev/null | cut -f1)
    bytes=${bytes:-0}

    pack=$(du -sb "$DIR/.git/objects/pack" 2>/dev/null | cut -f1)
    pack=${pack:-0}

    # A finalized .idx means the pack is fully received; we are checking out.
    if ls "$DIR"/.git/objects/pack/*.idx >/dev/null 2>&1; then
        phase="checkout"
        files=$(find "$DIR" -path "$DIR/.git" -prune -o -type f -print 2>/dev/null | wc -l)
        pct=$(( files * 100 / EST_FILES ))
        detail="$(printf "%'d" "$files") files"
    else
        phase="download"
        pct=$(( pack * 100 / EST_PACK ))
        detail="$(human "$pack") packed"
    fi
    [ "$pct" -gt 100 ] && pct=100

    # Throughput over the last sample window.
    dt=$(( now - last_time ))
    if [ "$dt" -ge 2 ]; then
        rate=$(( (bytes - last_bytes) / dt ))
        last_bytes=$bytes
        last_time=$now
        [ "$rate" -lt 0 ] && rate=0
        rate_s="$(human "$rate")/s"
    fi

    printf '\r  %-8s [%s] %3d%%  %-18s  %-10s  %s  %02d:%02d  ' \
        "$phase" "$(bar "$pct")" "$pct" "$detail" "${rate_s:-…}" \
        "$(human "$bytes")" $(( elapsed / 60 )) $(( elapsed % 60 ))

    if ! pgrep -x git >/dev/null 2>&1; then
        printf '\r  %-8s [%s] 100%%  %-18s  %-10s  %s  %02d:%02d  \n' \
            "done" "$(bar 100)" "$detail" "" "$(human "$bytes")" \
            $(( elapsed / 60 )) $(( elapsed % 60 ))
        printf '\n  clone finished.\n\n'
        exit 0
    fi

    sleep 2
done
