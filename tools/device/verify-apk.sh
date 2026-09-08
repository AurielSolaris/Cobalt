#!/usr/bin/env bash
# Verify the built APK is real. Three false "success" signals and one false
# "failure" signal so far in this project, so check the artifact itself.
APK=${APK:-/opt/cobalt/chromium/m140/src/out/Default/apks/ChromePublic.apk}

if [ ! -f "$APK" ]; then echo "MISSING: $APK"; exit 1; fi

echo "=== file"
ls -lh "$APK" | awk '{printf "  size    %s\n  time    %s %s %s\n", $5, $6, $7, $8}'
printf '  bytes   %s\n' "$(stat -c %s "$APK")"

echo
echo "=== is it actually a zip/APK?"
file "$APK" 2>/dev/null | sed 's/^/  /'
printf '  magic   %s\n' "$(head -c 2 "$APK" | xxd -p)"   # 504b = PK

echo
echo "=== zip integrity"
if command -v unzip >/dev/null; then
    unzip -t "$APK" 2>&1 | tail -2 | sed 's/^/  /'
    echo "  entries: $(unzip -l "$APK" 2>/dev/null | tail -1 | awk '{print $2}')"
else
    echo "  (unzip not installed)"
fi

echo
echo "=== key APK contents"
if command -v unzip >/dev/null; then
    for f in AndroidManifest.xml classes.dex resources.arsc; do
        if unzip -l "$APK" 2>/dev/null | grep -q " $f\$"; then
            printf '  OK      %s\n' "$f"
        else
            printf '  MISSING %s\n' "$f"
        fi
    done
    printf '  native libs: %s\n' "$(unzip -l "$APK" 2>/dev/null | grep -c '\.so$')"
    unzip -l "$APK" 2>/dev/null | grep '\.so$' | awk '{printf "    %10s  %s\n", $1, $4}' | head -5
fi

echo
echo "=== build finished cleanly?"
tail -c 1200 /opt/cobalt/build.log | tr '\r' '\n' | grep -iE 'Build Succeeded|Build Failure|steps failed|error' | tail -3 | sed 's/^/  /'
