#!/bin/sh
# check-engine-audit.sh — ENG-M01 engine-coupling audit gate (mobile)
#
# Ported from the desktop repo's .devin/check-engine-audit.sh.
# Keeps .devin/ENGINE.md honest: every Kotlin/Java source file that
# touches android.webkit.* must appear verbatim in ENGINE.md's
# coupling map, or match a whitelisted path substring from the doc's
# engine-audit-whitelist block.  A patch that adds an engine
# dependency without documenting it fails the gate.
#
# Cheap enough to run standalone: ./.devin/check-engine-audit.sh

cd "$(dirname "$0")/.." || exit 1
DOC=.devin/ENGINE.md

if [ ! -f "$DOC" ]; then
    echo "engine-audit: $DOC missing" >&2
    exit 1
fi

# The engine/ package is the engine-neutral boundary — webkit types
# must NEVER appear there.
if grep -rl 'android\.webkit' app/src/main/java/com/arktosbrowser/mobile/engine/ 2>/dev/null | grep -q .; then
    echo "engine-audit: FAIL — android.webkit reference inside engine/ (boundary violation)" >&2
    exit 1
fi

# Whitelist = first token of each line inside the
# <!-- engine-audit-whitelist ... --> block in ENGINE.md.
whitelist=$(sed -n '/engine-audit-whitelist/,/-->/p' "$DOC" | sed '1d;$d' | awk 'NF {print $1}')

files=$(grep -rl 'android\.webkit' app/src \
    --include='*.kt' --include='*.java' 2>/dev/null | sort)

missing=0
count=0
whitelisted=0
for f in $files; do
    count=$((count + 1))
    w=0
    for p in $whitelist; do
        case "$f" in *"$p"*) w=1; break ;; esac
    done
    if [ "$w" -eq 1 ]; then
        whitelisted=$((whitelisted + 1))
        continue
    fi
    if ! grep -qF "$f" "$DOC"; then
        echo "engine-audit: $f references android.webkit but is not documented in $DOC" >&2
        missing=$((missing + 1))
    fi
done

if [ "$missing" -gt 0 ]; then
    echo "engine-audit: FAIL — $missing undocumented android.webkit touchpoint(s)" >&2
    exit 1
fi
echo "engine-audit: PASS — $count files reference android.webkit ($whitelisted whitelisted, rest documented)"
exit 0
