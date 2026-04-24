#!/usr/bin/env bash
# Watches src/ for changes, rebuilds, and copies the JAR to the Claude2026Test instance.
# Run this in a terminal while developing. Restart MC to pick up changes.

set -euo pipefail

JAVA_HOME="/Users/iancoutinho/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home"
export JAVA_HOME

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODS_DIR=~/Library/Application\ Support/PrismLauncher/instances/Claude2026Test/minecraft/mods
JAR="$SCRIPT_DIR/build/libs/voiceannounce-1.12.2-1.0.0.jar"

echo "Watching src/ for changes — rebuild will copy JAR to Claude2026Test mods folder."
echo "Restart Minecraft to pick up changes."
echo ""

while true; do
    changed=false
    if [[ -f "$SCRIPT_DIR/.last_hot_reload" ]]; then
        newest=$(find "$SCRIPT_DIR/src" -type f -newer "$SCRIPT_DIR/.last_hot_reload" -print -quit 2>/dev/null || true)
        [[ -n "$newest" ]] && changed=true
    else
        changed=true
    fi

    if $changed; then
        echo "[$(date '+%H:%M:%S')] Change detected — building..."
        touch "$SCRIPT_DIR/.last_hot_reload"
        if (cd "$SCRIPT_DIR" && ./gradlew build -q 2>&1); then
            cp "$JAR" "$MODS_DIR/"
            echo "[$(date '+%H:%M:%S')] Build OK — JAR copied. Restart MC to reload."
        else
            echo "[$(date '+%H:%M:%S')] Build FAILED — check output above."
        fi
    fi

    sleep 2
done
