#!/usr/bin/env bash
# record-and-verify.sh — the pre-demo ritual.
#
# What it does (run 30 min before pitch on a live-internet machine):
#   1. ./gradlew recordDemo        — re-capture the 3 Codex traces into
#                                    src/main/resources/fallback/*.json. Requires
#                                    OPENAI_API_KEY in env.
#   2. sha256 each scenario file   — patch the committed hashes inside
#                                    DemoPathHealthCheck.kt between HASH_START / HASH_END
#                                    markers. This is how the in-IDE "Test Demo Path"
#                                    button knows the files haven't drifted since recording.
#   3. ./scripts/preflight.sh --fast — sanity-check the whole fallback ladder.
#
# Exits 0 only if every step succeeds. On any failure: prints a red line and exits 1.
#
# Usage:
#   export OPENAI_API_KEY=sk-...
#   ./scripts/record-and-verify.sh

set -eu

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
PROJECT_ROOT="$(cd "$(dirname "$SCRIPT")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ -t 1 ]; then
  C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YEL=$'\033[33m'; C_RST=$'\033[0m'
else
  C_GREEN=""; C_RED=""; C_YEL=""; C_RST=""
fi

step() { printf "\n%s==> %s%s\n" "$C_GREEN" "$1" "$C_RST"; }
die()  { printf "%s✗ %s%s\n" "$C_RED" "$1" "$C_RST" >&2; exit 1; }

# JAVA_HOME — auto-detect the Gradle-happy OpenJDK 21 if unset.
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi

# ----- Step 1: record -----
step "Step 1/3 — recording live Codex traces"
if [ -z "${OPENAI_API_KEY:-}" ]; then
  die "OPENAI_API_KEY unset — cannot re-record. Export it and re-run."
fi
"$PROJECT_ROOT/gradlew" recordDemo 2>&1 | tail -20 || die "gradlew recordDemo failed"

# Confirm the three files exist + are non-trivial.
FB="$PROJECT_ROOT/src/main/resources/fallback"
for scen in scenario-a-regression-INC4402 scenario-b-race-INC4388 scenario-c-fix-INC4417; do
  size=$(wc -c < "$FB/$scen.json" 2>/dev/null || echo 0)
  if [ "$size" -lt 50 ]; then
    die "$scen.json missing or empty ($size bytes)"
  fi
done
echo "${C_GREEN}  all 3 scenario files present${C_RST}"

# ----- Step 2: pin hashes into DemoPathHealthCheck.kt -----
step "Step 2/3 — pinning SHA-256 fingerprints into DemoPathHealthCheck.kt"

sha_for() {
  # Portable sha256 — macOS has shasum -a 256, most Linuxes have sha256sum.
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

HASH_A=$(sha_for "$FB/scenario-a-regression-INC4402.json")
HASH_B=$(sha_for "$FB/scenario-b-race-INC4388.json")
HASH_C=$(sha_for "$FB/scenario-c-fix-INC4417.json")

for h in "$HASH_A" "$HASH_B" "$HASH_C"; do
  if ! echo "$h" | grep -Eq '^[0-9a-f]{64}$'; then
    die "bad hash computed: $h"
  fi
done

KT="$PROJECT_ROOT/src/main/kotlin/com/stevenyang/datadogproactive/preflight/DemoPathHealthCheck.kt"
[ -f "$KT" ] || die "DemoPathHealthCheck.kt missing at $KT"

# In-place rewrite between HASH_START / HASH_END markers. Portable sed across macOS/Linux
# via a temp file + move (avoids -i differences).
python3 - "$KT" "$HASH_A" "$HASH_B" "$HASH_C" <<'PY' || die "hash patch failed"
import sys, re, pathlib
path = pathlib.Path(sys.argv[1])
hash_a, hash_b, hash_c = sys.argv[2], sys.argv[3], sys.argv[4]
text = path.read_text()

# Match start-of-line markers (line begins with whitespace then "// HASH_START").
# Avoids matching the same tokens when quoted inside a KDoc comment above.
start_re = re.compile(r'(?m)^[ \t]*// HASH_START\s*$')
end_re   = re.compile(r'(?m)^[ \t]*// HASH_END\s*$')
m_start = start_re.search(text)
m_end   = end_re.search(text)
if not m_start or not m_end or m_end.start() < m_start.start():
    print(f"markers not found", file=sys.stderr)
    sys.exit(2)
i, j = m_start.start(), m_end.start()

block = text[i:j]

def sub_line(block: str, key: str, new_hash: str) -> str:
    pattern = re.compile(rf'("{key}"\s+to\s+)(PLACEHOLDER_HASH|"[0-9a-f]{{64}}")(,?)')
    replacement = rf'\1"{new_hash}"\3'
    new_block, count = pattern.subn(replacement, block, count=1)
    if count != 1:
        print(f"failed to rewrite key {key} (count={count})", file=sys.stderr)
        sys.exit(3)
    return new_block

block = sub_line(block, "A", hash_a)
block = sub_line(block, "B", hash_b)
block = sub_line(block, "C", hash_c)

path.write_text(text[:i] + block + text[j:])
print(f"patched A={hash_a[:12]}... B={hash_b[:12]}... C={hash_c[:12]}...")
PY

echo "${C_GREEN}  pinned A=${HASH_A:0:12}... B=${HASH_B:0:12}... C=${HASH_C:0:12}...${C_RST}"

# ----- Step 3: preflight green-check -----
step "Step 3/3 — ./scripts/preflight.sh --fast"
if ! "$PROJECT_ROOT/scripts/preflight.sh" --fast; then
  die "preflight RED — review output above. Do NOT demo until this is GREEN."
fi

echo ""
echo "${C_GREEN}record-and-verify: GREEN — safe to demo.${C_RST}"
echo "${C_YEL}remember:${C_RST} commit the updated DemoPathHealthCheck.kt so the pinned hashes persist."
