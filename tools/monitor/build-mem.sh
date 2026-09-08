#!/usr/bin/env bash
echo "=== memory"
free -h
echo
echo "=== swap activity (si/so should be ~0)"
vmstat 1 3 | tail -2
echo
echo "=== top clang RSS (MB)"
ps -eo rss,comm --sort=-rss | awk 'NR>1 && $2 ~ /clang/ {print int($1/1024)" MB"}' | head -5
