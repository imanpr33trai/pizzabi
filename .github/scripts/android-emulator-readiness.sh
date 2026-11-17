#!/usr/bin/env bash
# Bounded Android emulator / ADB boot-readiness gate.
#
# Executed by the Stage 5 instrumentation job as the first line of the
# reactivecircus/android-emulator-runner `script` input, before the Gradle
# instrumentation command. That action splits `script` on newlines and runs
# each line as its own `sh -c`, so multi-line shell logic has to live here.
#
# Contract:
#   exit 0 -> emulator is booted and responsive; instrumentation may start.
#   exit 1 -> readiness failed. Diagnostics are printed to the job log and
#             written under ci-diagnostics/ (uploaded by the workflow with
#             `if: always()`). Diagnostic collection never changes the exit
#             status: the original failure reason is preserved.
#
# The single ADB restart below is bounded and followed by reconnection
# verification inside the same state loop; it is never repeated.
#
# Every adb invocation is wrapped in `timeout`: adb logcat/shell can block
# indefinitely ("- waiting for device -") when the device disappears, and an
# unbounded call would hang the whole step instead of failing it.
#
# Budgets are in seconds and overridable for local dry-runs; CI uses the
# documented defaults (worst case ~8 minutes before a clear failure).

set -u

DIAG_DIR="${PIZZORA_EMU_DIAG_DIR:-ci-diagnostics}"
DEVICE_APPEAR_BUDGET="${PIZZORA_EMU_DEVICE_APPEAR_BUDGET:-60}"
STATE_BUDGET="${PIZZORA_EMU_STATE_BUDGET:-210}"
OFFLINE_GRACE="${PIZZORA_EMU_OFFLINE_GRACE:-90}"
BOOT_BUDGET="${PIZZORA_EMU_BOOT_BUDGET:-180}"
CMD_TIMEOUT="${PIZZORA_EMU_CMD_TIMEOUT:-30}"

mkdir -p "$DIAG_DIR" || exit 1

# The action installs platform-tools before the script runs, but resolve adb
# defensively (ANDROID_HOME fallback) so a runner-image change produces a
# clear diagnostic instead of a bare "command not found".
ADB="$(command -v adb 2>/dev/null || true)"
if [ -z "$ADB" ]; then
  ADB="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools/adb"
fi

log() { printf '[emulator-readiness] %s\n' "$*"; }

get_state() {
  timeout "$CMD_TIMEOUT" "$ADB" get-state 2>/dev/null | tr -d '\r\n'
}

boot_props() {
  for prop in sys.boot_completed dev.bootcomplete ro.build.version.sdk ro.build.version.release; do
    printf '%s=%s\n' "$prop" "$(timeout "$CMD_TIMEOUT" "$ADB" shell getprop "$prop" 2>/dev/null | tr -d '\r\n')"
  done
}

# First non-blank line of a dump: dumpsys/pm output can start with blank
# lines, which must not read as "no output" for the responsiveness probes.
first_content_line() {
  grep -m1 -v '^[[:space:]]*$' | tr -d '\r\n'
}

collect_diagnostics() {
  reason="$1"
  log "READINESS FAILED: $reason"
  log "collecting diagnostics into $DIAG_DIR/ before failing"
  {
    echo "readiness failed at: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "reason: $reason"
    echo '--- adb devices -l'
    timeout "$CMD_TIMEOUT" "$ADB" devices -l 2>&1
    echo '--- adb get-state'
    timeout "$CMD_TIMEOUT" "$ADB" get-state 2>&1
    echo '--- boot properties'
    boot_props
    echo '--- dumpsys window (first 80 lines)'
    timeout "$CMD_TIMEOUT" "$ADB" shell dumpsys window 2>&1 | head -n 80
  } > "$DIAG_DIR/boot-readiness-failure.txt" 2>&1
  timeout "$CMD_TIMEOUT" "$ADB" logcat -d 2>&1 | tail -n 2000 > "$DIAG_DIR/logcat-tail.txt"
  echo '----- boot-readiness-failure.txt -----'
  cat "$DIAG_DIR/boot-readiness-failure.txt"
  echo '----- logcat tail (last 80 lines of captured buffer) -----'
  tail -n 80 "$DIAG_DIR/logcat-tail.txt"
  exit 1
}

# ---------------------------------------------------------------------------
# Phase 0: ADB server up.
# ---------------------------------------------------------------------------
timeout "$CMD_TIMEOUT" "$ADB" start-server >/dev/null 2>&1 || true
log 'adb server running; current device list:'
timeout "$CMD_TIMEOUT" "$ADB" devices -l

# ---------------------------------------------------------------------------
# Phase 1: emulator serial appears at all (bounded).
# ---------------------------------------------------------------------------
phase_start="$(date +%s)"
while :; do
  state="$(get_state)"
  if [ -n "$state" ]; then
    log "emulator visible to adb; state: $state"
    break
  fi
  now="$(date +%s)"
  if [ "$((now - phase_start))" -ge "$DEVICE_APPEAR_BUDGET" ]; then
    collect_diagnostics "emulator serial did not appear within ${DEVICE_APPEAR_BUDGET}s (ANDROID_SERIAL=${ANDROID_SERIAL:-unset})"
  fi
  sleep 2
done

# ---------------------------------------------------------------------------
# Phase 2: reach usable 'device' state. 'offline' is normal during early
# boot; if it persists beyond OFFLINE_GRACE, attempt exactly ONE bounded adb
# server restart and keep re-verifying reconnection in this loop.
# ---------------------------------------------------------------------------
phase_start="$(date +%s)"
restart_attempted=0
while :; do
  state="$(get_state)"
  case "$state" in
    device)
      log "device state reached: device"
      break
      ;;
    offline)
      now="$(date +%s)"
      if [ "$restart_attempted" -eq 0 ] && [ "$((now - phase_start))" -ge "$OFFLINE_GRACE" ]; then
        restart_attempted=1
        log "device offline for $((now - phase_start))s; performing the single bounded adb server restart"
        timeout "$CMD_TIMEOUT" "$ADB" kill-server >/dev/null 2>&1 || true
        timeout "$CMD_TIMEOUT" "$ADB" start-server >/dev/null 2>&1 || true
      fi
      ;;
    *)
      # Serial vanished or state unknown (e.g. mid-restart); keep polling.
      ;;
  esac
  now="$(date +%s)"
  if [ "$((now - phase_start))" -ge "$STATE_BUDGET" ]; then
    collect_diagnostics "device did not reach 'device' state within ${STATE_BUDGET}s (last state: '${state:-<none>}'; one adb restart attempted: $restart_attempted)"
  fi
  sleep 2
done

# ---------------------------------------------------------------------------
# Phase 3: Android framework boot completion (bounded).
# ---------------------------------------------------------------------------
phase_start="$(date +%s)"
boot_completed=""
while :; do
  boot_completed="$(timeout "$CMD_TIMEOUT" "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')"
  if [ "$boot_completed" = "1" ]; then
    log 'sys.boot_completed=1'
    break
  fi
  now="$(date +%s)"
  if [ "$((now - phase_start))" -ge "$BOOT_BUDGET" ]; then
    collect_diagnostics "sys.boot_completed did not become '1' within ${BOOT_BUDGET}s (last value: '${boot_completed:-<empty>}')"
  fi
  sleep 3
done

# ---------------------------------------------------------------------------
# Phase 4: system image, package manager and framework responsiveness.
# ---------------------------------------------------------------------------
sdk_level="$(timeout "$CMD_TIMEOUT" "$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')"
log "ro.build.version.sdk=$sdk_level (expected: 36)"
if [ "$sdk_level" != "36" ]; then
  collect_diagnostics "system image API level mismatch: got '$sdk_level', expected '36'"
fi

pm_probe="$(timeout "$CMD_TIMEOUT" "$ADB" shell pm list packages 2>/dev/null | first_content_line)"
if [ -z "$pm_probe" ]; then
  collect_diagnostics "package manager is not responsive ('pm list packages' produced no output)"
fi
log "package manager responsive (sample: $pm_probe)"

framework_probe="$(timeout "$CMD_TIMEOUT" "$ADB" shell dumpsys window 2>/dev/null | first_content_line)"
if [ -z "$framework_probe" ]; then
  collect_diagnostics "Android framework is not responsive ('dumpsys window' produced no content)"
fi
log "framework responsive (dumpsys window sample: $framework_probe)"

# ---------------------------------------------------------------------------
# Phase 5: success evidence for the diagnostics artifact.
# ---------------------------------------------------------------------------
{
  echo "readiness verified at: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo '--- adb devices -l'
  timeout "$CMD_TIMEOUT" "$ADB" devices -l 2>&1
  echo '--- adb get-state'
  timeout "$CMD_TIMEOUT" "$ADB" get-state 2>&1
  echo '--- boot properties'
  boot_props
} > "$DIAG_DIR/boot-readiness-success.txt" 2>&1

log 'emulator boot readiness verified; starting instrumentation tests'
exit 0
