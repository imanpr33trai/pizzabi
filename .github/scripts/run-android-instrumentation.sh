#!/usr/bin/env bash
# Android instrumentation runner for the Stage 5 emulator job.
#
# Executed as the single line of the reactivecircus/android-emulator-runner
# `script` input (that action splits `script` on newlines and runs each line
# as its own `sh -c`, so all logic must live in this file).
#
# Responsibilities (spec §5):
#   1. strict, predictable shell behavior;
#   2. EXIT trap installed BEFORE the instrumentation command runs;
#   3. execute the bounded boot-readiness gate;
#   4. record its result;
#   5. execute the exact required instrumentation command:
#        ./gradlew :app:connectedDebugAndroidTest -PpizzoraRequireRust=true --no-daemon
#   6. preserve its original exit code;
#   7. on any exit, collect diagnostics while the emulator is still alive
#      (the emulator-runner action terminates it after the script returns);
#   8. record the failed command and its exit code;
#   9. save Gradle output and test reports into ci-diagnostics/;
#  10. exit with the original instrumentation failure code.
#
# Diagnostic collection inside the trap is bounded (see
# collect-android-diagnostics.sh) and can never change the exit status:
# the original code is re-exited explicitly.

set -u
set -o pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

DIAG_DIR="${PIZZORA_EMU_DIAG_DIR:-ci-diagnostics}"
READINESS_SCRIPT="$REPO_ROOT/.github/scripts/android-emulator-readiness.sh"
COLLECTOR="$REPO_ROOT/.github/scripts/collect-android-diagnostics.sh"
# The exact, required instrumentation command (do not weaken or substitute).
GRADLE_COMMAND=(./gradlew :app:connectedDebugAndroidTest -PpizzoraRequireRust=true --no-daemon)

log() { printf '[instrumentation] %s\n' "$*"; }

# on_exit: original status is $? of the script body; collect diagnostics once
# (bounded), then re-exit with the ORIGINAL code so diagnostics can never
# convert a failure into success or vice versa.
on_exit() {
  rc=$?
  trap - EXIT
  log "exiting with original status $rc; collecting pre-termination diagnostics (bounded)"
  bash "$COLLECTOR" pre
  exit "$rc"
}
trap on_exit EXIT

mkdir -p "$DIAG_DIR" || exit 1

# ---------------------------------------------------------------------------
# 1. Boot-readiness gate.
# ---------------------------------------------------------------------------
log 'running emulator/ADB boot-readiness gate'
if bash "$READINESS_SCRIPT"; then
  printf 'PASSED rc=0\n' > "$DIAG_DIR/.readiness-result"
  log 'readiness gate PASSED'
else
  readiness_rc=$?
  printf 'FAILED rc=%s\n' "$readiness_rc" > "$DIAG_DIR/.readiness-result"
  log "readiness gate FAILED (rc=$readiness_rc); instrumentation will NOT start"
  log "readiness diagnostics were written to $DIAG_DIR/ (uploaded with if: always())"
  exit 1
fi

# ---------------------------------------------------------------------------
# 2. Instrumentation command (exact required command, output captured live).
# ---------------------------------------------------------------------------
printf '%s\n' "${GRADLE_COMMAND[*]}" > "$DIAG_DIR/.gradle-cmd"
log "running: ${GRADLE_COMMAND[*]}"
"${GRADLE_COMMAND[@]}" 2>&1 | tee "$DIAG_DIR/gradle-live-output.txt"
gradle_rc="${PIPESTATUS[0]}"
printf 'rc=%s\n' "$gradle_rc" > "$DIAG_DIR/.gradle-result"

if [ "$gradle_rc" -eq 0 ]; then
  log 'instrumentation PASSED (rc=0); reports are in app/build/outputs/androidTest-results'
else
  log "instrumentation FAILED (rc=$gradle_rc); full output: $DIAG_DIR/gradle-test-output.txt"
fi

exit "$gradle_rc"
