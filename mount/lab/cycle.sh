#!/usr/bin/env bash
# Repeatability harness: run the full opt-in lifecycle N times on a fresh copy of a vanilla AEM.
#
#   preflight -> enable -> start -> verify(opted in) -> stop -> status -> disable -> start -> verify(opted out) -> stop
#
# usage: cycle.sh <vanilla-aem-home> <work-dir> <validator-url> [rounds=2] [port=4502]
# env:   OAKCHAIN_AEM_AUTH=user:password (default admin:admin for a lab instance)
#        JAVA_HOME (defaults to the java on PATH)
#
# The vanilla source is only copied, never modified. The work dir is replaced.
set -uo pipefail

SRC="${1:?vanilla AEM home}"
WORK="${2:?work dir}"
VALIDATOR="${3:?validator url}"
ROUNDS="${4:-2}"
PORT="${5:-4502}"
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="$(ls "$HERE"/../target/oak-chain-connector.mount-*.jar | grep -v sources | head -1)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
export OAKCHAIN_AEM_AUTH="${OAKCHAIN_AEM_AUTH:-admin:admin}"
QS_JAR="$(cd "$SRC" && ls cq-quickstart-*.jar aem*-*.jar 2>/dev/null | head -1)"
LOG="$WORK.cycle.log"
SUMMARY=()

[ -f "$JAR" ] || { echo "build the bundle first (mvn install)"; exit 2; }
[ -n "$QS_JAR" ] || { echo "no quickstart jar in $SRC"; exit 2; }
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then echo "port $PORT is busy"; exit 2; fi

tool() { "$JAVA" -jar "$JAR" "$@" >>"$LOG" 2>&1; }

step() { # name, command...
  local name="$1"; shift
  local t0=$SECONDS
  echo "== $name" >>"$LOG"
  if "$@"; then SUMMARY+=("PASS  $(printf '%-28s' "$name") $((SECONDS - t0))s"); return 0
  else SUMMARY+=("FAIL  $(printf '%-28s' "$name") $((SECONDS - t0))s (see $LOG)"); return 1; fi
}

start_aem() {
  (cd "$WORK" && exec "$JAVA" -Xmx4g -Djava.awt.headless=true -jar "$QS_JAR" -nofork -nobrowser -nointeractive -p "$PORT" \
      >>"$LOG.aem" 2>&1) &
  AEM_PID=$!
}

stop_aem() { # the subshell exec'd java, so AEM_PID is the JVM; SIGTERM runs the quickstart shutdown hook
  [ -n "${AEM_PID:-}" ] || return 0
  local pid="$AEM_PID"
  AEM_PID=""
  kill -TERM "$pid" 2>/dev/null
  for _ in $(seq 1 90); do kill -0 "$pid" 2>/dev/null || return 0; sleep 2; done
  return 1
}

trap 'stop_aem' EXIT

: >"$LOG"; : >"$LOG.aem"
rm -rf "$WORK" && cp -a "$SRC" "$WORK" || { echo "copy failed"; exit 2; }
echo "work copy: $WORK  (log: $LOG)"

for round in $(seq 1 "$ROUNDS"); do
  step "r$round preflight"          tool preflight "$WORK" --validator "$VALIDATOR"
  step "r$round enable"             tool enable "$WORK" --validator "$VALIDATOR" || break
  start_aem
  step "r$round verify opted-in"    tool verify "$WORK" --aem-url "http://localhost:$PORT" --write-probe --timeout 600
  step "r$round stop"               stop_aem
  step "r$round status (no drift)"  tool status "$WORK"
  step "r$round disable"            tool disable "$WORK"
  start_aem
  step "r$round verify opted-out"   tool verify "$WORK" --aem-url "http://localhost:$PORT" --expect vanilla --timeout 600
  step "r$round stop"               stop_aem
done

echo
printf '%s\n' "${SUMMARY[@]}"
! printf '%s\n' "${SUMMARY[@]}" | grep -q '^FAIL'
