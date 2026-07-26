#!/bin/bash
# Arc-Sim launcher (macOS). Double-click this file (or run it from Terminal) to start the app.
#
# Finder marks downloaded .command files as untrusted by default; if double-clicking does nothing
# or macOS refuses to run it, right-click ArcSim.command -> Open once to approve it, or run
# `chmod +x ArcSim.command` in Terminal first.

set -e
cd "$(dirname "$0")"

JAR="ArcSim.jar"
MIN_JAVA_MAJOR=17

if [ ! -f "$JAR" ]; then
    osascript -e "display alert \"Arc-Sim\" message \"$JAR was not found next to this launcher. Make sure ArcSim.command stays in the same folder as ArcSim.jar.\"" >/dev/null 2>&1 || \
        echo "ERROR: $JAR not found next to this launcher."
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    osascript -e 'display alert "Arc-Sim" message "No Java runtime was found on this Mac. Install a free Java 17 or newer runtime (e.g. from https://adoptium.net) and try again." as critical' >/dev/null 2>&1 || \
        echo "ERROR: java not found on PATH. Install a Java 17+ runtime from https://adoptium.net"
    exit 1
fi

JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)\..*/\1/')
if [ -z "$JAVA_MAJOR" ]; then
    JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)".*/\1/')
fi
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt "$MIN_JAVA_MAJOR" ] 2>/dev/null; then
    osascript -e "display alert \"Arc-Sim\" message \"Java $JAVA_MAJOR was found, but Arc-Sim needs Java $MIN_JAVA_MAJOR or newer. Install an updated runtime from https://adoptium.net and try again.\" as critical" >/dev/null 2>&1 || \
        echo "ERROR: Java $JAVA_MAJOR found; Arc-Sim needs Java $MIN_JAVA_MAJOR+."
    exit 1
fi

# Minimize the Terminal window this script is running in, so the GUI (not the terminal) ends up
# with focus once it opens. Best-effort only: harmless no-op if the frontmost terminal app isn't
# Terminal.app (e.g. iTerm) or AppleScript/Automation permission hasn't been granted yet -- output
# still lands in the (now-minimized) window either way. Backgrounded so it never delays launch.
osascript -e 'tell application "Terminal" to set miniaturized of front window to true' >/dev/null 2>&1 &

exec java -jar "$JAR"
