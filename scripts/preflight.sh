#!/usr/bin/env bash
# preflight.sh — demo-safety pre-flight check.
#
# Exits 0 if every required check passes (GREEN). Exits 1 on any RED failure.
# YELLOW warnings (e.g. missing DD_APP_KEY) do NOT fail the check; the demo path
# does not need Datadog write access.
#
# Usage:
#   ./scripts/preflight.sh              # full run incl. `./gradlew test`
#   ./scripts/preflight.sh --fast       # skip `./gradlew test` (20s win)
#   ./scripts/preflight.sh --verbose    # print resolved absolute paths
#
# Run this 30 min before the pitch; run `record-and-verify.sh` for the ritual.

set -u

FAST=0
VERBOSE=0
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=1 ;;
    --verbose) VERBOSE=1 ;;
    -h|--help)
      grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
  esac
done

# Locate project root relative to this script. Resolves through symlinks.
SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
PROJECT_ROOT="$(cd "$(dirname "$SCRIPT")/.." && pwd)"
cd "$PROJECT_ROOT"

# Color helpers — degrade gracefully if stdout is not a tty.
if [ -t 1 ]; then
  C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YEL=$'\033[33m'; C_DIM=$'\033[2m'; C_RST=$'\033[0m'
else
  C_GREEN=""; C_RED=""; C_YEL=""; C_DIM=""; C_RST=""
fi

RED_COUNT=0
YEL_COUNT=0

pad() {
  # Left-pad a label to 32 chars.
  local s="$1"
  printf "%-32s" "$s"
}

pass() {
  local label="$1"; local detail="${2:-}"
  printf "[%s✓%s] %s%s\n" "$C_GREEN" "$C_RST" "$(pad "$label")" \
    "$([ -n "$detail" ] && echo "${C_DIM}($detail)${C_RST}")"
}

warn() {
  local label="$1"; local detail="${2:-}"
  YEL_COUNT=$((YEL_COUNT+1))
  printf "[%s!%s] %s%s\n" "$C_YEL" "$C_RST" "$(pad "$label")" \
    "$([ -n "$detail" ] && echo "${C_YEL}$detail${C_RST}")"
}

fail() {
  local label="$1"; local detail="${2:-}"
  RED_COUNT=$((RED_COUNT+1))
  printf "[%s✗%s] %s%s\n" "$C_RED" "$C_RST" "$(pad "$label")" \
    "$([ -n "$detail" ] && echo "${C_RED}$detail${C_RST}")"
}

vpath() {
  [ "$VERBOSE" = 1 ] && printf "    %s→ %s%s\n" "$C_DIM" "$1" "$C_RST"
}

# ----- 1. JDK 21 ---------------------------------------------------------------
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JVER=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)
elif command -v java >/dev/null 2>&1; then
  JVER=$(java -version 2>&1 | head -1)
else
  JVER=""
fi

if echo "$JVER" | grep -Eq '"21\.'; then
  JVER_NUM=$(echo "$JVER" | sed -nE 's/.*"([0-9][0-9.]+)".*/\1/p')
  pass "JDK 21" "$JVER_NUM"
else
  fail "JDK 21" "not found — set JAVA_HOME to OpenJDK 21"
fi
vpath "JAVA_HOME=${JAVA_HOME:-<unset>}"

# ----- 2. Gradle wrapper -------------------------------------------------------
if [ -x "$PROJECT_ROOT/gradlew" ]; then
  GVER=$("$PROJECT_ROOT/gradlew" --version 2>/dev/null | awk '/^Gradle /{print $2; exit}')
  if [ -n "$GVER" ]; then
    pass "Gradle wrapper" "$GVER"
  else
    fail "Gradle wrapper" "./gradlew --version failed"
  fi
else
  fail "Gradle wrapper" "./gradlew missing or not executable"
fi

# ----- 3. Fixture JSON ---------------------------------------------------------
FIXTURE="$PROJECT_ROOT/src/main/resources/fixtures/datadog-fixtures.json"
if [ -f "$FIXTURE" ]; then
  # parse + count via python (always present on macOS / the demo laptop)
  FIX_STATS=$(python3 - "$FIXTURE" <<'PY' 2>/dev/null
import json, sys
with open(sys.argv[1]) as f:
    d = json.load(f)
incs = len(d.get("incidents", []))
evs = len(d.get("events", []))
mois = d.get("methods_of_interest", [])
names = {m.get("fq_name", "") for m in mois}
# real tsre method check — accept any Payment*.charge variant
has_real = any(n.endswith("PaymentService.charge") or n.endswith("PaymentServiceImpl.charge") for n in names)
print(f"{incs}|{evs}|{int(has_real)}")
PY
  )
  if [ -n "$FIX_STATS" ]; then
    INC_COUNT="${FIX_STATS%%|*}"
    REST="${FIX_STATS#*|}"
    EV_COUNT="${REST%%|*}"
    HAS_REAL="${REST##*|}"
    if [ "$INC_COUNT" -ge 2000 ] && [ "$HAS_REAL" = "1" ]; then
      pass "Fixture (>=2000 incidents)" "$INC_COUNT / $EV_COUNT events"
    elif [ "$INC_COUNT" -lt 2000 ]; then
      fail "Fixture (>=2000 incidents)" "only $INC_COUNT incidents"
    else
      fail "Fixture methods_of_interest" "PaymentService.charge not present"
    fi
  else
    fail "Fixture JSON" "does not parse as JSON"
  fi
else
  fail "Fixture JSON" "missing: $FIXTURE"
fi
vpath "$FIXTURE"

# ----- 4. Fallback scenarios ---------------------------------------------------
FB_DIR="$PROJECT_ROOT/src/main/resources/fallback"
missing_scen=""
for scen in a-regression-INC4402 b-race-INC4388 c-fix-INC4417; do
  if [ ! -f "$FB_DIR/scenario-$scen.json" ]; then
    missing_scen="$missing_scen $scen"
  fi
done
if [ -z "$missing_scen" ]; then
  pass "Fallback scenarios" "a/b/c all present"
else
  fail "Fallback scenarios" "missing:$missing_scen"
fi
vpath "$FB_DIR"

# ----- 5. .env + OPENAI_API_KEY -----------------------------------------------
ENV_FILE="$PROJECT_ROOT/.env"
read_env_key() {
  # stdout: value if key present+non-empty in .env, else empty
  local k="$1"
  [ -f "$ENV_FILE" ] || return 0
  awk -F= -v key="$k" '
    /^[[:space:]]*#/ { next }
    {
      sub(/^[[:space:]]*/,""); sub(/[[:space:]]*$/,"")
      if ($0 == "") next
      n = index($0, "=")
      if (n == 0) next
      lhs = substr($0, 1, n-1)
      rhs = substr($0, n+1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", lhs)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", rhs)
      gsub(/^["\x27]|["\x27]$/, "", rhs)
      if (lhs == key && rhs != "") { print rhs; exit }
    }' "$ENV_FILE"
}

if [ -f "$ENV_FILE" ]; then
  OAI_ENV=$(read_env_key OPENAI_API_KEY)
  OAI_RUN="${OPENAI_API_KEY:-}"
  if [ -n "$OAI_ENV" ]; then
    pass "OPENAI_API_KEY in .env" "set"
  elif [ -n "$OAI_RUN" ]; then
    pass "OPENAI_API_KEY in env" "set (runtime)"
  else
    fail "OPENAI_API_KEY" "not found in .env or env"
  fi

  DD_ENV=$(read_env_key DD_API_KEY)
  DD_RUN="${DD_API_KEY:-}"
  if [ -n "$DD_ENV" ] || [ -n "$DD_RUN" ]; then
    pass "DD_API_KEY in .env" "set"
  else
    warn "DD_API_KEY in .env" "empty — USE_REAL_DATADOG toggle will fail"
  fi

  DDAPP_ENV=$(read_env_key DD_APP_KEY)
  DDAPP_RUN="${DD_APP_KEY:-}"
  if [ -n "$DDAPP_ENV" ] || [ -n "$DDAPP_RUN" ]; then
    pass "DD_APP_KEY in .env" "set"
  else
    warn "DD_APP_KEY in .env" "empty — RealDatadogService read path will degrade silently"
  fi
else
  fail ".env file" "missing at project root"
fi
vpath "$ENV_FILE"

# ----- 6. tsre-microservices clone --------------------------------------------
TSRE="$PROJECT_ROOT/demo-repos/tsre-microservices"
if [ -d "$TSRE/.git" ] || [ -d "$TSRE/src" ] || [ -f "$TSRE/README.md" ]; then
  pass "tsre-microservices clone" "demo-repos/tsre-microservices"
else
  fail "tsre-microservices clone" "missing — run ./gradlew prepareDemoRepo"
fi
vpath "$TSRE"

# ----- 7. plugin.xml extension points -----------------------------------------
PLUGIN_XML="$PROJECT_ROOT/src/main/resources/META-INF/plugin.xml"
if [ -f "$PLUGIN_XML" ]; then
  # use python xml.etree for valid-parse + extension checks in one shot
  EXT_RESULT=$(python3 - "$PLUGIN_XML" <<'PY' 2>/dev/null
import sys, xml.etree.ElementTree as ET
path = sys.argv[1]
try:
    tree = ET.parse(path)
except ET.ParseError as e:
    print(f"PARSE_ERROR|{e}")
    sys.exit(0)
root = tree.getroot()
# Required presence checks.
found = {
    "lineMarkerProvider": False,
    "editorNotificationProvider": False,
    "toolWindow[Datadog Risk]": False,
    "action[DatadogProactive.AnalyzePrRisk]": False,
    "applicationService[FixtureLoader]": False,
    "projectService[CodexAgentLoop]": False,
    "notificationGroup[Datadog Proactive]": False,
}
for ext_block in root.iter("extensions"):
    for child in ext_block:
        tag = child.tag.rsplit("}", 1)[-1]
        if tag == "codeInsight.lineMarkerProvider":
            found["lineMarkerProvider"] = True
        if tag == "editorNotificationProvider":
            found["editorNotificationProvider"] = True
        if tag == "toolWindow" and child.get("id") == "Datadog Risk":
            found["toolWindow[Datadog Risk]"] = True
        if tag == "applicationService" and "FixtureLoader" in (child.get("serviceImplementation","")):
            found["applicationService[FixtureLoader]"] = True
        if tag == "projectService" and "CodexAgentLoop" in (child.get("serviceInterface","")):
            found["projectService[CodexAgentLoop]"] = True
        if tag == "notificationGroup" and child.get("id") == "Datadog Proactive":
            found["notificationGroup[Datadog Proactive]"] = True
for actions in root.iter("actions"):
    for a in actions.iter("action"):
        if a.get("id") == "DatadogProactive.AnalyzePrRisk":
            found["action[DatadogProactive.AnalyzePrRisk]"] = True
total = len(found)
got = sum(1 for v in found.values() if v)
missing = [k for k, v in found.items() if not v]
print(f"{got}|{total}|{','.join(missing)}")
PY
  )
  if [ -z "$EXT_RESULT" ]; then
    fail "plugin.xml extensions" "python check failed"
  elif [ "${EXT_RESULT%%|*}" = "PARSE_ERROR" ]; then
    fail "plugin.xml extensions" "XML parse error"
  else
    GOT="${EXT_RESULT%%|*}"
    REST="${EXT_RESULT#*|}"
    TOTAL="${REST%%|*}"
    MISS="${REST#*|}"
    if [ "$GOT" = "$TOTAL" ]; then
      pass "plugin.xml extensions" "$GOT of $TOTAL required"
    else
      fail "plugin.xml extensions" "$GOT/$TOTAL; missing: $MISS"
    fi
  fi
else
  fail "plugin.xml" "missing: $PLUGIN_XML"
fi
vpath "$PLUGIN_XML"

# ----- 8. ./gradlew test (skipped in --fast) ----------------------------------
if [ "$FAST" = 1 ]; then
  printf "[%s–%s] %s%s\n" "$C_DIM" "$C_RST" "$(pad "./gradlew test")" \
    "${C_DIM}(skipped — --fast)${C_RST}"
else
  if "$PROJECT_ROOT/gradlew" test --quiet >/tmp/preflight-gradle-test.log 2>&1; then
    pass "./gradlew test" "passed"
  else
    fail "./gradlew test" "failed — see /tmp/preflight-gradle-test.log"
  fi
fi

echo ""
if [ "$RED_COUNT" -gt 0 ]; then
  printf "%sPREFLIGHT: RED%s — %d check(s) failed, %d warning(s).\n" \
    "$C_RED" "$C_RST" "$RED_COUNT" "$YEL_COUNT"
  exit 1
elif [ "$YEL_COUNT" -gt 0 ]; then
  printf "%sPREFLIGHT: YELLOW%s — %d warning(s). Demo path is safe; real-Datadog flip may degrade.\n" \
    "$C_YEL" "$C_RST" "$YEL_COUNT"
  exit 0
else
  printf "%sPREFLIGHT: GREEN%s — safe to demo.\n" "$C_GREEN" "$C_RST"
  exit 0
fi
