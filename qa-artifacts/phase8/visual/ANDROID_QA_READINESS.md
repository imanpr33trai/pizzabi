# PIZZORA Phase 8 Android QA Readiness

Date: 2026-11-27 (UTC)
Workspace: `/workspace/pizzabi`
Scope: environment preparation only. No app source, backend source, shared source, or Rust source was modified. No app was installed. No Phase 8 screenshots were captured because W6/W7 UI work is still in progress.

## Overall status

**READY** for later Phase 8 runtime QA. Emulator `emulator-5554` is online, booted, detached from the QA shell, and left running at API 36 / Android 16.

| Item | Status |
| --- | --- |
| Ubuntu host | PASS |
| JDK 21 | PASS |
| Android SDK / platform-tools / cmdline-tools | PASS |
| ADB | PASS |
| `/dev/kvm` access | PASS |
| `pizzora_api36_qa` AVD and API 36 image | PASS |
| Emulator runtime libraries | PASS after temporary, non-installing extraction of missing Ubuntu shared libraries |
| Emulator boot (`sys.boot_completed=1`) | PASS |
| Baseline app install | NOT INSTALLED |
| Backend API availability | BLOCKED for runtime scenarios (only PostgreSQL is up; no API port responded) |
| GPU acceleration | NOT AVAILABLE on host; emulator uses SwiftShader software rendering |
| TalkBack | INSTALLED but DISABLED |
| Phase 8 screenshots | NOT CAPTURED (by instruction) |
| Phase 8 functional/a11y scenarios | NOT TESTED (preparation task only) |

## Environment audit

Command:

```bash
cat /etc/os-release
nproc
lscpu | sed -n '1,25p'
free -h
df -h / /workspace /tmp
for v in JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT; do printf '%s=%s\n' "$v" "${!v-}"; done
java -version 2>&1
javac -version 2>&1
```

Output:

```text
PRETTY_NAME="Ubuntu 24.04.5 LTS"
VERSION_ID="24.04"
VERSION_CODENAME=noble
4
Architecture:                            x86_64
CPU(s):                                  4
Model name:                              Intel(R) Xeon(R) 6975P-C
Virtualization:                          VT-x
Hypervisor vendor:                       KVM
Virtualization type:                     full
               total        used        free      shared  buff/cache   available
Mem:            15Gi       1.8Gi       1.6Gi        98Mi        12Gi        13Gi
Swap:             0B          0B          0B
Filesystem            Size  Used Avail Use% Mounted on
overlay               145G   74G   72G  51% /
/dev/mapper/cow-root  145G   74G   72G  51% /workspace
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ANDROID_HOME=/home/remote/Android/Sdk
ANDROID_SDK_ROOT=/home/remote/Android/Sdk
openjdk version "21.0.13.1" 2026-11-17
OpenJDK Runtime Environment (build 21.0.13.1+2-1-24.04.1-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.13.1+2-1-24.04.1-Ubuntu, mixed mode, sharing)
javac 21.0.13.1
```

Command:

```bash
for c in adb emulator avdmanager sdkmanager docker gradle; do printf '%s: ' "$c"; command -v "$c" || echo MISSING; done
```

Output:

```text
adb: /home/remote/Android/Sdk/platform-tools/adb
emulator: /home/remote/Android/Sdk/emulator/emulator
avdmanager: /home/remote/Android/Sdk/cmdline-tools/latest/bin/avdmanager
sdkmanager: /home/remote/Android/Sdk/cmdline-tools/latest/bin/sdkmanager
docker: /usr/bin/docker
gradle: MISSING
```

`gradle` is MISSING from PATH only; the repository provides `./gradlew`. This did not block emulator readiness.

Command:

```bash
adb version
```

Output:

```text
Android Debug Bridge version 1.0.41
Version 37.0.3-16887417
Installed as /home/remote/Android/Sdk/platform-tools/adb
Running on Linux 7.0.0-1018-aws (x86_64)
```

Command:

```bash
sdkmanager --list_installed
```

Relevant output:

```text
build-tools;36.0.0                          | 36.0.0  | Android SDK Build-Tools 36
emulator                                    | 37.3.10 | Android Emulator
platform-tools                              | 37.0.3  | Android SDK Platform-Tools
platforms;android-36                        | 2       | Android SDK Platform 36
system-images;android-36;google_apis;x86_64 | 7       | Google APIs Intel x86_64 Atom System Image
```

## KVM and GPU/device acceleration

Command:

```bash
ls -l /dev/kvm
stat -c '%A %U:%G %n' /dev/kvm
id
for p in /dev/dri/card0 /dev/dri/renderD128 /dev/nvidia0; do ls -l "$p" 2>&1; done
```

Output:

```text
crw-rw---- 1 root hostkvm 10, 232 Nov 27 10:42 /dev/kvm
crw-rw---- root:hostkvm /dev/kvm
uid=1001(remote) gid=1001(remote) groups=1001(remote),27(sudo),116(hostdocker),993(hostkvm)
ls: cannot access '/dev/dri/card0': No such file or directory
ls: cannot access '/dev/dri/renderD128': No such file or directory
ls: cannot access '/dev/nvidia0': No such file or directory
```

KVM is available and the `remote` user is in `hostkvm`. No host GPU device node is exposed. The emulator was run with `-gpu swiftshader`; emulator log reported:

```text
INFO         | Graphics Adapter Vendor Google (Google Inc.)
INFO         | Graphics Adapter Android Emulator OpenGL ES Translator (Google SwiftShader)
INFO         | Graphics API Version OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)
```

## Emulator runtime-library blocker and resolution

Initial exact failure:

```bash
emulator -version > /tmp/opencode/emulator-version-direct.out 2>&1; s=$?; printf 'exit=%s\n' "$s"; cat /tmp/opencode/emulator-version-direct.out
```

Output:

```text
exit=127
/home/remote/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64: error while loading shared libraries: libpulse.so.0: cannot open shared object file: No such file or directory
```

No package was installed and no system configuration was changed. Missing libraries were downloaded as `.deb` files and extracted only under `/tmp/opencode/android-qa-libs/root`:

```bash
mkdir -p /tmp/opencode/android-qa-libs/debs /tmp/opencode/android-qa-libs/root
cd /tmp/opencode/android-qa-libs/debs
apt-get download libpulse0 libxkbfile1
apt-get download libasyncns0 libsndfile1
apt-get download libvorbis0a libvorbisenc2 libopus0 libogg0 libmp3lame0 libflac12t64 libmpg123-0t64
for deb in ./*.deb; do dpkg-deb -x "$deb" /tmp/opencode/android-qa-libs/root; done
```

One intermediate attempt using legacy package names failed exactly as follows; it was retried with the Ubuntu 24.04 `t64` names and succeeded:

```text
E: Can't select candidate version from package libflac12 as it has no candidate
E: Can't select candidate version from package libmpg123-0 as it has no candidate
download_exit=100
```

Successful library environment:

```bash
export LD_LIBRARY_PATH=/home/remote/Android/Sdk/emulator/lib64:/home/remote/Android/Sdk/emulator/lib64/qt/lib:/home/remote/Android/Sdk/emulator/lib64/gles_swiftshader:/home/remote/Android/Sdk/emulator/lib64/vulkan:/tmp/opencode/android-qa-libs/root/usr/lib/x86_64-linux-gnu:/tmp/opencode/android-qa-libs/root/usr/lib/x86_64-linux-gnu/pulseaudio
ldd /home/remote/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64 | grep 'not found' || echo '(none)'
emulator -version
```

Output:

```text
(none)
Android emulator version 37.3.10.0 (build_id 16970787) (CL:N/A)
Copyright (C) 2006-2024 The Android Open Source Project and many others.
```

Exit status for successful `emulator -version`: `0`.

## AVD identity

Files:

```bash
cat /home/remote/.android/avd/pizzora_api36_qa.ini
grep -E '^(target|abi.type|hw.cpu.arch|hw.lcd.width|hw.lcd.height|hw.lcd.density|image.sysdir.1|tag.id|disk.dataPartition)' /home/remote/.android/avd/pizzora_api36_qa.avd/config.ini
grep -E 'ro.build.id|ro.build.version.sdk|ro.build.version.release|ro.product.cpu.abi' /home/remote/Android/Sdk/system-images/android-36/google_apis/x86_64/build.prop
```

Output:

```text
avd.ini.encoding=UTF-8
path=/home/remote/.android/avd/pizzora_api36_qa.avd
path.rel=avd/pizzora_api36_qa.avd
target=android-36
target=android-36
abi.type=x86_64
hw.cpu.arch=x86_64
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
image.sysdir.1=system-images/android-36/google_apis/x86_64/
tag.id=google_apis
disk.dataPartition.path=<temp>
disk.dataPartition.size=10G
ro.build.id=BE2A.250530.026.F3
ro.build.version.sdk=36
ro.build.version.release=16
ro.product.cpu.abi=x86_64
```

## Emulator boot

No `-wipe-data`, factory reset, or user-data deletion was used. The first foreground/background-tool launch reached boot completion but was SIGTERM'd by the tool's 120 s limit; it was relaunched detached and left running. Final launch command:

```bash
setsid nohup env LD_LIBRARY_PATH=/home/remote/Android/Sdk/emulator/lib64:/home/remote/Android/Sdk/emulator/lib64/qt/lib:/home/remote/Android/Sdk/emulator/lib64/gles_swiftshader:/home/remote/Android/Sdk/emulator/lib64/vulkan:/tmp/opencode/android-qa-libs/root/usr/lib/x86_64-linux-gnu:/tmp/opencode/android-qa-libs/root/usr/lib/x86_64-linux-gnu/pulseaudio emulator -avd pizzora_api36_qa -port 5554 -no-window -noaudio -no-boot-anim -gpu swiftshader -camera-back none -camera-front none > /tmp/opencode/pizzora_api36_qa-emulator-final.log 2>&1 < /dev/null & echo "launcher_pid=$!"
```

Output:

```text
launcher_pid=46229
```

Observed process:

```text
remote     46229       1 99 12:03 ?        00:00:07 /home/remote/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless -avd pizzora_api36_qa -port 5554 -no-window -noaudio -no-boot-anim -gpu swiftshader -camera-back none -camera-front none
```

Readiness loop command (abbreviated only in repetition; this is the exact loop shape executed):

```bash
serial=emulator-5554
ready=0
for i in $(seq 1 120); do
  state=$(adb -s "$serial" get-state 2>/dev/null)
  boot=''
  if [ "$state" = "device" ]; then
    boot=$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  fi
  printf '[%s] attempt=%s state=%s boot_completed=%s\n' "$(date -u +%H:%M:%SZ)" "$i" "${state:-<none>}" "${boot:-<none>}"
  if [ "$state" = "device" ] && [ "$boot" = "1" ]; then ready=1; break; fi
  sleep 5
done
printf 'ready=%s\n' "$ready"
```

Relevant output from the final detached launch:

```text
[12:03:39Z] attempt=1 state=device boot_completed=1
ready=1
```

Emulator log relevant output from the final detached launch:

```text
INFO         | Graphics Adapter Vendor Google (Google Inc.)
INFO         | Graphics Adapter Android Emulator OpenGL ES Translator (Google SwiftShader)
INFO         | Setting display: 0 configuration to: 1080x2400, dpi: 420x420
INFO         | Loading snapshot 'default_boot'...
INFO         | Successfully loaded snapshot 'default_boot' using 2089 ms
```

The first launch had no saved snapshot and cold-booted successfully in `64079 ms`; the final detached launch reused the generated snapshot. Neither path used a wipe.

## Final readiness verification

Command:

```bash
adb devices -l
adb -s emulator-5554 shell getprop ro.build.version.release
adb -s emulator-5554 shell getprop ro.build.version.sdk
adb -s emulator-5554 shell getprop ro.build.id
adb -s emulator-5554 shell getprop ro.product.model
adb -s emulator-5554 shell getprop ro.product.cpu.abi
adb -s emulator-5554 shell getprop sys.boot_completed
adb -s emulator-5554 shell getprop dev.bootcomplete
adb -s emulator-5554 shell uptime
```

Output:

```text
List of devices attached
emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:2
16
36
BE2A.250530.026.F3
sdk_gphone64_x86_64
x86_64
1
1
 12:03:46 up 2 min,  0 users,  load average: 42.83, 12.41, 4.29
```

Serial: `emulator-5554`
API level: `36`
Android release: `16`
Build ID: `BE2A.250530.026.F3`
AVD: `pizzora_api36_qa`

Relevant filtered logcat command:

```bash
adb -s emulator-5554 logcat -d -t 300 | grep -Ei 'boot|zygote|systemserver|package.*pizzora|E/' | tail -40
```

No `E/` line or app-package line appeared in the sampled output. Representative boot lines:

```text
11-27 12:02:21.062  1030  1030 V SystemUIBootTiming: Topologically start Core Startables took to complete: 17285ms
11-27 12:02:21.288  1030  1030 V SystemUIBootTiming: StartServices took to complete: 17511ms
11-27 12:02:21.604   472   472 D Zygote  : Forked child process 1869
```

## Baseline app install status

Application ID from source (read-only check):

```text
/workspace/pizzabi/app/build.gradle.kts:14:    applicationId = "com.aistudio.pizzora.xkqzmw"
```

Command:

```bash
adb -s emulator-5554 shell pm list packages com.aistudio.pizzora.xkqzmw
printf 'pm_list_exit=%s\n' "$?"
adb -s emulator-5554 shell pm path com.aistudio.pizzora.xkqzmw
printf 'pm_path_exit=%s\n' "$?"
stat -c '%n %s bytes %y' /workspace/pizzabi/app/build/outputs/apk/debug/app-debug.apk
```

Output:

```text
pm_list_exit=0
pm_path_exit=1
/workspace/pizzabi/app/build/outputs/apk/debug/app-debug.apk 25524280 bytes 2026-11-24 17:15:36.000000000 +0000
```

Baseline status: **NOT INSTALLED**. The `pm list` result was empty and `pm path` returned exit `1`. The pre-existing debug APK was not installed by QA.

## Docker and backend availability

Command:

```bash
docker info --format '{{.ServerVersion}} {{.OperatingSystem}} {{.Architecture}}'
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
for url in http://127.0.0.1:8080/health http://127.0.0.1:8080/ http://127.0.0.1:8000/health http://127.0.0.1:3000/health; do printf '%s -> ' "$url"; curl -sS -o /dev/null -w '%{http_code}\n' --max-time 5 "$url"; done
```

Output:

```text
28.0.4 Ubuntu 24.04.5 LTS x86_64
NAMES          IMAGE               STATUS             PORTS
pizzora-pg18   postgres:18-alpine  Up 51 minutes      0.0.0.0:5432->5432/tcp, [::]:5432->5432/tcp
linux-remote   ubuntu:24.04        Up About an hour
http://127.0.0.1:8080/health -> 000
http://127.0.0.1:8080/ -> 000
http://127.0.0.1:8000/health -> 000
http://127.0.0.1:3000/health -> 000
```

Docker daemon is available and PostgreSQL is running, but no backend API answered on common local ports. Backend-dependent runtime scenarios remain BLOCKED until the Phase 8 API is started.

## Accessibility snapshot (readiness only; not full a11y QA)

Command:

```bash
adb -s emulator-5554 shell settings get secure enabled_accessibility_services
adb -s emulator-5554 shell settings get secure accessibility_enabled
adb -s emulator-5554 shell pm list packages talkback
adb -s emulator-5554 shell wm density
adb -s emulator-5554 shell wm size
adb -s emulator-5554 shell ime list -s
adb -s emulator-5554 shell settings get secure default_input_method
```

Output:

```text
null
0
package:com.google.android.marvin.talkbackoverlay
package:com.google.android.marvin.talkback
Physical density: 420
Physical size: 1080x2400
com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService
com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

TalkBack packages are present but accessibility is disabled. Touch-target sizing, 1.5x/2.0x font scaling, large display size, keyboard/IME behavior, and insets were **NOT TESTED** in this preparation task. Orca was not used.

## Phase 8 screenshot status

No screenshots were captured. No screenshot paths or dimensions are reported because W6/W7 UI work is still in progress and the task explicitly forbids final Phase 8 screenshots.

## Blockers and next-step notes

1. **Backend API not running:** no HTTP response on ports 8080, 8000, or 3000; only PostgreSQL container `pizzora-pg18` is up. Start the Phase 8 backend before backend-dependent runtime scenarios.
2. **App not installed:** baseline package `com.aistudio.pizzora.xkqzmw` is absent. Install only after W6/W7 UI work is ready and with explicit QA authorization.
3. **Host GPU unavailable:** emulator uses SwiftShader software rendering. This is acceptable for headless readiness but may affect visual performance assertions.
4. **Backlog:** all Phase 8 cart/coupon/checkout/a11y functional scenarios remain NOT TESTED by this preparation task.

Evidence path: `/workspace/pizzabi/qa-artifacts/phase8/visual/ANDROID_QA_READINESS.md`
