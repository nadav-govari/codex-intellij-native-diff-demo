#!/bin/sh
set -eu

PLUGIN_SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
IDEA_JBR='/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home'

if [ ! -x "$IDEA_JBR/bin/java" ]; then
  echo "IntelliJ IDEA 2026.2 with its bundled Java runtime is required." >&2
  exit 1
fi

python3 -m unittest discover -s "$PLUGIN_SOURCE_ROOT/tests" -v

cd "$PLUGIN_SOURCE_ROOT/intellij-plugin"
JAVA_HOME="$IDEA_JBR" ./gradlew test buildPlugin verifyPluginProjectConfiguration --console=plain

echo
echo "IntelliJ plugin:"
echo "$PLUGIN_SOURCE_ROOT/intellij-plugin/build/distributions/codex-intellij-native-diff-0.2.0.zip"
