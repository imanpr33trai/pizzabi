#!/usr/bin/env bash
# Android instrumentation diagnostics collector.
#
# Produces the canonical postmortem file set under ci-diagnostics/:
#
#   adb-postmortem.txt          adb device state, boot properties, dumpsys
#                               excerpts, emulator process list
#   emulator-logcat.txt         device logcat dump (while the emulator lives)
#   instrumentation-summary.txt what ran, which command failed, exit codes
#   gradle-test-output.txt      full Gradle instrumentation output
#   report-inventory.txt        inventory (and copies) of XML/HTML test reports
#
# Usage: collect-android-diagnostics.sh [pre|post]
#   pre  (default for the wrapper's EXIT trap): the emulator may still be
#        alive; every file is written fresh.
#   post (workflow collect step, after android-emulator-runner terminated the
#        emulator): existing pre-termination files are APPENDED to, never
#        overwritten, and any file that was never produced is created with an
#        explanatory message.
#
# Contract:
#   - exits 0 on every path; a diagnostics failure must never change the
#     instrumentation job outcome.
#   - every adb invocation is wrapped in `timeout` (adb logcat/shell block
#     indefinitely with "- waiting for device -" when no device exists) and
#     each call's exit status / timeout flag is recorded next to its output.
#   - an overall budget bounds the whole collection; sections are skipped
#     with a note once it is exhausted.
#   - never collects keystore/credential material.

set -u

MODE="${1:-post}"
case "$MODE" in
  pre|post) ;;
  *) printf 'usage: %s [pre|post]\n' "$0" >&2; exit 2 ;;
esac

DIAG_DIR="${PIZZORA_EMU_DIAG_DIR:-ci-diagnostics}"
ADB_CMD_TIMEOUT="${PIZZORA_EMU_ADB_CMD_TIMEOUT:-30}"
LOGCAT_TIMEOUT="${PIZZORA_EMU_LOGCAT_TIMEOUT:-60}"
OVERALL_BUDGET="${PIZZORA_EMU_DIAG_BUDGET:-300}"
APP_PACKAGE="${PIZZORA_APP_PACKAGE:-com.aistudio.pizzora.xkqzmw}"

mkdir -p "$DIAG_DIR" || exit 0

START="$(date +%s)"
DEADLINE=$((START + OVERALL_BUDGET))
deadline_ok() { [ "$(date +%s)" -lt "$DEADLINE" ]; }

log() { printf '[diagnostics:%s] %s\n' "$MODE" "$*"; }

# ---------------------------------------------------------------------------
# ADB resolution: the emulator-runner action installs platform-tools and puts
# adb on the job PATH, but this collector also runs in steps where the action
# never ran (or was removed from the image). Resolve defensively and record
# the fact when unavailable instead of failing.
# ---------------------------------------------------------------------------
ADB_BIN="${PIZZORA_EMU_ADB_BIN:-$(command -v adb 2>/dev/null || true)}"
if [ -z "$ADB_BIN" ]; then
  ADB_BIN="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools/adb"
fi
adb_available() { [ -n "$ADB_BIN" ] && [ -x "$ADB_BIN" ]; }

# run_adb FILE LABEL ARGS...
# Appends "[adb LABEL] <status>" plus the full output of one bounded adb call
# to FILE. Never aborts the collection.
run_adb() {
  file="$1"; label="$2"; shift 2
  if ! deadline_ok; then
    printf '[adb %s] SKIPPED: overall diagnostics budget exhausted\n' "$label" >> "$file"
    return 0
  fi
  if ! adb_available; then
    printf '[adb %s] SKIPPED: adb binary not available or not executable (resolved: %s)\n' "$label" "$ADB_BIN" >> "$file"
    return 0
  fi
  out="$(mktemp)"
  timeout "$ADB_CMD_TIMEOUT" "$ADB_BIN" "$@" >"$out" 2>&1
  rc=$?
  case "$rc" in
    0)       printf '[adb %s] exit=0\n' "$label" >> "$file" ;;
    124)     printf '[adb %s] TIMED OUT after %ss (adb blocks indefinitely without a device)\n' "$label" "$ADB_CMD_TIMEOUT" >> "$file" ;;
    137|143) printf '[adb %s] killed (rc=%s)\n' "$label" "$rc" >> "$file" ;;
    *)       printf '[adb %s] exit=%s (nonzero is expected once the emulator is gone)\n' "$label" "$rc" >> "$file" ;;
  esac
  cat "$out" >> "$file"
  rm -f "$out"
  return 0
}

# ---------------------------------------------------------------------------
# adb-postmortem.txt
# ---------------------------------------------------------------------------
collect_adb_postmortem() {
  file="$DIAG_DIR/adb-postmortem.txt"
  if [ "$MODE" = post ] && [ -s "$file" ]; then
    printf '\n===== post-termination addendum (%s) =====\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$file"
  else
    {
      echo "collected at: $(date -u '+%Y-%m-%dT%H:%M:%SZ') (mode: $MODE)"
      echo "adb binary: $ADB_BIN"
      echo "ANDROID_HOME=${ANDROID_HOME:-<unset>}"
      echo "ANDROID_SERIAL=${ANDROID_SERIAL:-<unset>}"
      echo '--- /dev/kvm'
      ls -l /dev/kvm 2>&1 || true
      echo '--- adb version'
    } > "$file" 2>&1
    run_adb "$file" 'version' version
  fi
  echo '--- adb devices -l' >> "$file"
  run_adb "$file" 'devices -l' devices -l
  echo '--- adb get-state' >> "$file"
  run_adb "$file" 'get-state' get-state
  echo '--- boot properties' >> "$file"
  for prop in sys.boot_completed dev.bootcomplete ro.build.version.sdk ro.build.version.release ro.product.cpu.abi; do
    echo "[$prop]" >> "$file"
    run_adb "$file" "getprop $prop" shell getprop "$prop"
  done
  echo '--- dumpsys window (first 60 lines)' >> "$file"
  run_adb "$file" 'dumpsys window' shell dumpsys window
  echo '--- dumpsys activity (first 120 lines)' >> "$file"
  run_adb "$file" 'dumpsys activity' shell dumpsys activity
  echo "--- dumpsys package $APP_PACKAGE (first 150 lines)" >> "$file"
  run_adb "$file" 'dumpsys package' shell dumpsys package "$APP_PACKAGE"
  echo '--- emulator processes (ps)' >> "$file"
  ps -ef 2>/dev/null | grep -E '[q]emu|[e]mulator' >> "$file" 2>&1 || echo '(no emulator/qemu process found)' >> "$file"
  return 0
}

# ---------------------------------------------------------------------------
# emulator-logcat.txt — the only file that REQUIRES a live emulator; in post
# mode a pre-termination copy is preserved and the fresh attempt (which is
# expected to fail) is appended.
# ---------------------------------------------------------------------------
collect_logcat() {
  file="$DIAG_DIR/emulator-logcat.txt"
  if [ "$MODE" = post ] && [ -s "$file" ]; then
    printf '\n===== post-termination addendum (%s) =====\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$file"
  else
    echo "logcat dump started at: $(date -u '+%Y-%m-%dT%H:%M:%SZ') (mode: $MODE)" > "$file"
  fi
  if ! deadline_ok; then
    printf '[logcat] SKIPPED: overall diagnostics budget exhausted\n' >> "$file"
    return 0
  fi
  if ! adb_available; then
    printf '[logcat] SKIPPED: adb binary not available (resolved: %s). The wrapper captures logcat before the emulator is terminated; see boot-readiness-failure.txt/logcat-tail.txt when readiness failed.\n' "$ADB_BIN" >> "$file"
    return 0
  fi
  out="$(mktemp)"
  timeout "$LOGCAT_TIMEOUT" "$ADB_BIN" logcat -d >"$out" 2>&1
  rc=$?
  case "$rc" in
    0)   printf '[logcat] exit=0 (%s bytes captured)\n' "$(wc -c <"$out")" >> "$file" ;;
    124) printf '[logcat] TIMED OUT after %ss; capturing whatever was buffered\n' "$LOGCAT_TIMEOUT" >> "$file" ;;
    *)   printf '[logcat] exit=%s (nonzero is expected once the emulator is gone)\n' "$rc" >> "$file" ;;
  esac
  tail -n 4000 "$out" >> "$file"
  rm -f "$out"
  if [ "$(wc -c <"$file")" -lt 200 ]; then
    printf '[logcat] NOTE: capture is empty; the emulator was most likely already terminated.\n' >> "$file"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# instrumentation-summary.txt
# ---------------------------------------------------------------------------
collect_summary() {
  file="$DIAG_DIR/instrumentation-summary.txt"
  if [ "$MODE" = post ] && [ -s "$file" ]; then
    printf '\n===== post-termination addendum (%s) =====\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$file"
  else
    {
      echo "PIZZORA Android instrumentation summary (mode: $MODE)"
      echo "collected at: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
      if [ -n "${GITHUB_SERVER_URL:-}" ] && [ -n "${GITHUB_REPOSITORY:-}" ] && [ -n "${GITHUB_RUN_ID:-}" ]; then
        echo "workflow run: $GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
      fi
      echo "runner: $(uname -a)"
    } > "$file"
  fi
  {
    echo '--- readiness gate'
    if [ -f "$DIAG_DIR/.readiness-result" ]; then
      cat "$DIAG_DIR/.readiness-result"
    else
      echo 'UNKNOWN: the instrumentation wrapper never recorded a readiness result (the emulator-runner action likely failed before executing .github/scripts/run-android-instrumentation.sh).'
    fi
    if [ -f "$DIAG_DIR/boot-readiness-failure.txt" ]; then
      grep -m1 '^reason:' "$DIAG_DIR/boot-readiness-failure.txt" || echo '(readiness failure file present without a reason line)'
      echo "full readiness diagnostics: $DIAG_DIR/boot-readiness-failure.txt"
    fi
    echo '--- instrumentation command'
    if [ -f "$DIAG_DIR/.gradle-cmd" ]; then
      cat "$DIAG_DIR/.gradle-cmd"
    else
      echo './gradlew :app:connectedDebugAndroidTest -PpizzoraRequireRust=true --no-daemon (not executed)'
    fi
    echo '--- instrumentation result'
    if [ -f "$DIAG_DIR/.gradle-result" ]; then
      cat "$DIAG_DIR/.gradle-result"
    else
      echo 'UNKNOWN: Gradle never recorded a result (it did not run).'
    fi
    echo '--- artifact contents'
    ls -l "$DIAG_DIR" 2>/dev/null || true
  } >> "$file" 2>&1
  return 0
}

# ---------------------------------------------------------------------------
# gradle-test-output.txt
# ---------------------------------------------------------------------------
collect_gradle_output() {
  file="$DIAG_DIR/gradle-test-output.txt"
  if [ "$MODE" = post ] && [ -s "$file" ]; then
    printf '\n===== post-termination addendum (%s): no fresh Gradle output at this point; see the captured output above =====\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$file"
    return 0
  fi
  if [ -f "$DIAG_DIR/gradle-live-output.txt" ]; then
    cp "$DIAG_DIR/gradle-live-output.txt" "$file" || true
    printf '\n[captured live by .github/scripts/run-android-instrumentation.sh]\n' >> "$file"
  else
    {
      echo "Gradle instrumentation output was NOT captured."
      echo "The wrapper script (.github/scripts/run-android-instrumentation.sh) did not reach the Gradle command;"
      echo "the emulator-runner action most likely failed before running it. See instrumentation-summary.txt."
    } > "$file"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# report-inventory.txt (+ copies of the report directories)
# ---------------------------------------------------------------------------
collect_report_inventory() {
  file="$DIAG_DIR/report-inventory.txt"
  if [ "$MODE" = post ] && [ -s "$file" ]; then
    printf '\n===== post-termination addendum (%s): inventory unchanged after emulator termination =====\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$file"
    return 0
  fi
  {
    echo "test report inventory, collected at: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    found=0
    for d in app/build/outputs/androidTest-results app/build/reports/androidTests; do
      echo "--- $d"
      if [ -d "$d" ]; then
        found=1
        find "$d" -type f -printf '%p (%s bytes)\n' 2>/dev/null | sort || true
        base="$(basename "$d")"
        if [ ! -e "$DIAG_DIR/$base" ]; then
          cp -r "$d" "$DIAG_DIR/$base" 2>/dev/null || echo "(copy into artifact failed)" || true
        fi
      else
        echo '(directory not present — the instrumentation command did not run to the reporting stage)'
      fi
    done
    if [ "$found" -eq 0 ]; then
      echo
      echo 'No XML/HTML instrumentation reports were produced.'
      echo 'Either the instrumentation command never ran (see instrumentation-summary.txt)'
      echo 'or it failed before generating reports (e.g. build, APK install or native library failure).'
    else
      xml_count="$(find app/build/outputs/androidTest-results app/build/reports/androidTests -name 'TEST-*.xml' 2>/dev/null | wc -l)"
      echo "TEST-*.xml result files: $xml_count"
    fi
  } > "$file" 2>&1
  return 0
}

# ---------------------------------------------------------------------------
# Main: each section is individually fault-tolerant; the order puts the
# emulator-dependent captures first so they win the budget race.
# ---------------------------------------------------------------------------
log "collecting into $DIAG_DIR/ (budget ${OVERALL_BUDGET}s)"
collect_summary
collect_adb_postmortem
collect_logcat
collect_gradle_output
collect_report_inventory
log 'collection finished'
exit 0
