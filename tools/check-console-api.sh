#!/usr/bin/env bash
SRC=${SRC:-/opt/cobalt/chromium/m140/src}
cd "$SRC" || exit 1
echo "=== the browser-side hook for console messages"
grep -rn "DidAddMessageToConsole" content/public/browser/web_contents_observer.h \
  content/public/browser/web_contents_delegate.h 2>/dev/null | head -8
echo
echo "=== message levels available"
grep -rn -A8 "enum ConsoleMessageLevel" third_party/blink/public/mojom/devtools/console_message.mojom 2>/dev/null | head -12
echo
echo "=== what the callback carries"
grep -rn -B4 -A10 "virtual void OnDidAddMessageToConsole" content/public/browser/web_contents_observer.h 2>/dev/null | head -20
echo
echo "=== does Android already surface this?"
grep -rln "DidAddMessageToConsole\|AddMessageToConsole" chrome/browser/android components/embedder_support/android 2>/dev/null | head -5
