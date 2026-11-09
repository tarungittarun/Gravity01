#!/usr/bin/env bash
# Boot an Android emulator on a CI runner and leave it attached to adb.
#
# The constraint that shapes this script is acceleration. An x86 system image *requires* it - QEMU2 will
# refuse to start an x86 AVD under pure software emulation on an x86 host - so the useful behaviour is to
# detect /dev/kvm (present on GitHub's ubuntu runners, absent on many container and self-hosted ones),
# repair its permissions if the runner user cannot open it, and say "this runner cannot boot this image"
# in three seconds rather than after a twenty-five minute timeout that cannot succeed.
#
# Exit codes: 0 booted and ready; 3 no emulator here (missing acceleration, install failure, boot timeout).
# A workflow should treat 3 as "untested, with a reason" and never as a pass, and never as a product bug.
#
# Env in:  API IMG ABI DEVICE AVD_NAME BOOT_TIMEOUT MEMORY CORES NO_GPU KEEP_LOGCAT
# Env out: E2E_ACCEL (kvm|off), written to $GITHUB_OUTPUT when present.
set -uo pipefail

API=${API:-34}
IMG=${IMG:-google_apis}
ABI=${ABI:-x86_64}
DEVICE=${DEVICE:-pixel_6}
AVD_NAME=${AVD_NAME:-e2e}
# A cold API-34 x86_64 first boot on a 2 vCPU runner with software GL is slow, and 2 GB of heap makes it
# slower still (dex2oat thrashes), so the budget is generous and the memory is what the runner can actually
# give: a row that times out is reported as untested, which is a worse outcome than a row that takes nine
# minutes. Both numbers are env-overridable so a self-hosted runner can tighten them.
BOOT_TIMEOUT=${BOOT_TIMEOUT:-1500}
MEMORY=${MEMORY:-4096}
CORES=${CORES:-2}

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
# Where the SDK tools actually are is a property of the runner image, and it has moved: run 696591a died with
# exit 127 because $SDK/cmdline-tools/latest/bin did not exist on that image, so `sdkmanager` was not found
# while installing the very system image the image does not ship either. Searching is the only answer that
# survives the next image refresh.
find_tool() {
  local name=$1 cand
  for cand in "$SDK"/cmdline-tools/latest/bin/"$name" "$SDK"/cmdline-tools/*/bin/"$name" \
              "$SDK"/tools/bin/"$name" "$HOME"/android-sdk/cmdline-tools/latest/bin/"$name"; do
    [ -x "$cand" ] && { echo "$cand"; return 0; }
  done
  command -v "$name" 2>/dev/null && return 0
  return 1
}
SDKMGR=$(find_tool sdkmanager || true)
AVDMGR=$(find_tool avdmanager || true)
if [ -z "$SDKMGR" ]; then
  # Nothing on the image at all: fetch the command line tools once, into a root the job owns. dl.google.com is
  # the only host that can serve this, and the URL is overridable because the build number in it ages.
  CTL_ZIP=${CTL_ZIP:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}
  echo "::notice::no sdkmanager on this image - bootstrapping command line tools from ${CTL_ZIP##*/}"
  mkdir -p "$HOME/android-sdk/cmdline-tools"
  if curl -fsSL --retry 3 -o /tmp/ctl.zip "$CTL_ZIP" && unzip -q -o /tmp/ctl.zip -d /tmp/ctl \
     && [ -d /tmp/ctl/cmdline-tools ]; then
    rm -rf "$HOME/android-sdk/cmdline-tools/latest"
    mv /tmp/ctl/cmdline-tools "$HOME/android-sdk/cmdline-tools/latest"
    SDK="$HOME/android-sdk"
    SDKMGR=$(find_tool sdkmanager || true)
    AVDMGR=$(find_tool avdmanager || true)
    echo "   bootstrapped: sdkmanager=${SDKMGR:-none} avdmanager=${AVDMGR:-none}"
  else
    echo "::error::could not download/unzip $CTL_ZIP - no way to install a system image on this runner"
  fi
fi
# The SDK root has to be writable, or the install has to be done as root: GitHub's runners allow passwordless
# sudo, which keeps the preinstalled SDK (and its already-downloaded platform) usable as-is.
SUDO=""
SDK_ROOT_ARG=""
if [ ! -w "$SDK" ]; then
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    SUDO="sudo -E"
    echo "::notice::$SDK is not writable by the job user - installing through sudo"
  else
    SDK="$HOME/android-sdk"; mkdir -p "$SDK"
    SDK_ROOT_ARG="--sdk_root=$SDK"
    echo "::notice::$SDK is not writable and sudo is unavailable - installing into $SDK instead"
  fi
fi
[ -n "$AVDMGR" ] || AVDMGR="$SDKMGR"   # same bin directory; avdmanager may be absent and that is reported below
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$(dirname "${SDKMGR:-$SDK/cmdline-tools/latest/bin/sdkmanager}"):$SDK/platform-tools:$SDK/emulator:$PATH"

# Some runner images own /dev/kvm as root:kvm with mode 0660 and do not add the job user to the kvm group.
# That reads as "no acceleration" and silently costs the whole row, so fix it before deciding.
if [ ! -w /dev/kvm ] && [ -e /dev/kvm ] && command -v sudo >/dev/null 2>&1; then
  sudo -n chmod 666 /dev/kvm >/dev/null 2>&1 || true
fi
if [ -w /dev/kvm ] && [ -r /dev/kvm ]; then
  ACCEL=kvm
  echo "::notice::/dev/kvm is available - hardware acceleration on"
else
  ACCEL=off
  echo "::warning::no usable /dev/kvm on this runner"
  case "$ABI" in
    x86*)
      # An x86 image cannot boot without it, so waiting is not caution, it is wasted CI time. Say which
      # knob to turn instead: a KVM-capable runner, or an arm64 image on an arm64 host.
      echo "::error::$ABI needs hardware acceleration; this runner has none, so this row is untested rather than failed. Use a KVM-capable runner (GitHub's ubuntu-latest qualifies) or an arm64 image on arm64 hardware."
      exit 3
      ;;
  esac
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "accel=$ACCEL" >> "$GITHUB_OUTPUT"
  echo "kvm=$([ "$ACCEL" = kvm ] && echo true || echo false)" >> "$GITHUB_OUTPUT"
fi

PKGS=("platform-tools" "emulator" "platforms;android-${API}" "system-images;android-${API};${IMG};${ABI}")
echo "== installing: ${PKGS[*]}"

# What each package has to leave on disk for the run to be able to continue. The list is deliberately
# checked *after* sdkmanager rather than trusting its exit code: on GitHub's images platform-tools, the
# emulator and android-34 are already installed, `sdkmanager` then closes its stdin early, and `yes` dies
# of a broken pipe - which, under `pipefail`, reads as "the install failed" and was what killed every row of
# run 38120714896. A component that exists is a component that is installed, whatever the tool returned.
path_ok() {
  case "$1" in
    platform-tools) [ -x "$SDK/platform-tools/adb" ] ;;
    emulator) [ -x "$SDK/emulator/emulator" ] ;;
    platforms*) [ -d "$SDK/platforms/android-${API}" ] ;;
    system-images*)
      # A directory is not an image: the runner's SDK can carry the path with no build.prop, and trusting the
      # path is how "already present" ended the install step while the emulator then found nothing to boot.
      [ -f "$SDK/system-images/android-${API}/${IMG}/${ABI}/build.prop" ] ;;
    *) false ;;
  esac
}

sdkmanager_says() {
  # One line per progress tick, with \r, so a raw tail of that log is 40 kB of bars and buries the sentence
  # that matters. Progress is dropped, errors are kept.
  tr '\r' '\n' < /tmp/sdkmanager.log 2>/dev/null \
    | grep -vE "Loading (package|local repository)|Fetch remote repository|Computing updates|Unzipping|Downloading|%\]$" \
    | grep -v '^[[:space:]]*$' | tail -12
}

install_pkg() {
  local pkg=$1 rc=0
  path_ok "$pkg" && { echo "   $pkg already present"; return 0; }
  if [ -z "$SDKMGR" ]; then
    echo "::error::no sdkmanager anywhere (searched $SDK/cmdline-tools/*/bin, $SDK/tools/bin, PATH) - cannot install $pkg"
    return 1
  fi
  # The found tool, the chosen root, and sudo when the root demands it - the three things that were each
  # guessed at separately in the runs before 696591a.
  # An old API image can stall in sdkmanager without a progress line. Bound each package
  # so the row can post its real install failure instead of GitHub canceling the entire job.
  yes 2>/dev/null | timeout 120 $SUDO "$SDKMGR" $SDK_ROOT_ARG --install "$pkg" > /tmp/sdkmanager.log 2>&1
  rc=${PIPESTATUS[1]}
  if [ "$rc" != 0 ] && [[ "$pkg" = emulator || "$pkg" = system-images* ]] &&
     ! path_ok "$pkg" &&
     { [ "$rc" = 124 ] || grep -q 'ZipFile unknown archive' /tmp/sdkmanager.log; }; then
    # Hosted runners intermittently stall at 10% while fetching the *valid*
    # Google APIs image or receive a truncated emulator ZIP. Make exactly one
    # bounded fresh attempt; keep both logs and fail visibly if it still fails.
    # This never retries the old unavailable default/x86_64 API 24 image.
    local retry_limit=120
    [ "$pkg" = emulator ] && [ "$rc" = 124 ] && retry_limit=60
    echo "::warning::$pkg fetch failed ($rc); clearing partial SDK download and retrying once for ${retry_limit}s"
    rm -rf "$SDK/.temp"
    yes 2>/dev/null | timeout "$retry_limit" $SUDO "$SDKMGR" $SDK_ROOT_ARG --install "$pkg" > /tmp/sdkmanager-retry.log 2>&1
    rc=${PIPESTATUS[1]}
    cat /tmp/sdkmanager-retry.log >> /tmp/sdkmanager.log
  fi
  [ "$rc" = 124 ] && echo "::warning::sdkmanager timed out after 120s installing $pkg; image/files present=$(path_ok "$pkg" && echo yes || echo no)"
  [ "$rc" = 127 ] && echo "::error::sdkmanager could not be executed ($SDKMGR, rc=127)" && return 1
  # sdkmanager's own verdict first. Only if it *says* it failed do we look at the filesystem again, because
  # a path guess that does not match the SDK's real layout must not be able to turn a successful install
  # into "this api level has no image" - which is what cost run 38121167143 the release and density rows.
  [ "$rc" = 0 ] && return 0
  if path_ok "$pkg"; then echo "   $pkg installed (exit $rc, files present)"; return 0; fi
  echo "sdkmanager ($SDKMGR ${SDK_ROOT_ARG:-root=default}) exit $rc for $pkg:"; sdkmanager_says
  return 1
}

for p in "${PKGS[@]}"; do
  if ! install_pkg "$p"; then
    case "$p" in
      system-images*)
        if [ "$ABI" = x86 ]; then
          echo "::error::api $API/$IMG x86 image did not install; $(sdkmanager_says | tr '\n' ' ' | cut -c1-240)"
          exit 3
        fi
        # Not every API level published an x86_64 google_apis image (android-24 is a common offender), and
        # a 32-bit system image runs the app just as well for these purposes. Falling back keeps the minSdk
        # row a real row instead of a packaging accident in the image list.
        firstImageWhy=$(sdkmanager_says | tr '\n' ' ' | cut -c1-300)
        echo "::notice::x86_64 image install did not complete (${firstImageWhy:-no diagnostic}); trying x86"
        ABI=x86
        if ! install_pkg "system-images;android-${API};${IMG};x86"; then
          echo "::error::sdkmanager could not install api ${API}/${IMG} image: x86_64=${firstImageWhy:-unknown}; x86=$(sdkmanager_says | tr '\n' ' ' | cut -c1-300)"
          tail -25 /tmp/sdkmanager.log
          exit 3
        fi
        continue
        ;;
    esac
    echo "::error::sdkmanager could not install $p"
    sdkmanager_says
    exit 3
  fi
done

# Every tool this script needs, checked before anything else and named in an annotation if absent: a bare
# "Process completed with exit code 127" five log pages later is the least informative way a row can die,
# and it cost run ee4d09f a whole round trip.
MISSING=""
for c in adb timeout grep sed awk tr find; do
  command -v "$c" >/dev/null 2>&1 || MISSING="$MISSING $c"
done
[ -n "$MISSING" ] && echo "::error::not on PATH for this job:$MISSING (SDK=$SDK)"
for c in "$SDK/emulator/emulator" "$SDK/platform-tools/adb"; do
  [ -x "$c" ] || echo "::warning::missing or not executable: $c"
done
if [ -n "${GITHUB_PATH:-}" ]; then
  # The steps after this one call `adb` by name, and the PATH export below only lives inside this script.
  # Run 30b75c1 proved it: the emulator booted, and the install and instrument steps then died with 127 -
  # "command not found" for a tool this row had just used. Publishing the paths makes them real steps.
  { echo "$SDK/platform-tools"; echo "$SDK/emulator"; dirname "${SDKMGR:-/dev/null}"; } | while read -r d; do
    [ -d "$d" ] && echo "$d" >> "$GITHUB_PATH"
  done
fi
echo "== tools: sdk=${SDK:-unset} sdkmanager=${SDKMGR:-NONE} avdmanager=${AVDMGR:-NONE} sudo=${SUDO:-not-needed} root_arg=${SDK_ROOT_ARG:-default}"
[ -f /tmp/env.txt ] && echo "   (probe: $(grep -m1 'writable=' /tmp/env.txt 2>/dev/null | cut -c1-90))"

# The job log is not reachable from where a red run gets diagnosed (it redirects to blob storage); the
# annotation on the check run is. Anything the row's failure hinges on therefore goes into an `::error::`
# or `::warning::` line on one line and capped, not only into the log.
lastlines() {
  tail -c 1400 "$1" 2>/dev/null | tr '\r' '\n' | grep -v '^[[:space:]]*$' | tail -5 | tr '\n' ' ' | cut -c1-460
}

echo "== creating AVD $AVD_NAME (api $API, $IMG/$ABI, device $DEVICE)"
AVD_PKG="system-images;android-${API};${IMG};${ABI}"
[ -d "$SDK/system-images/android-${API}/${IMG}/${ABI}" ] \
  || echo "::warning::no image directory at $SDK/system-images/android-${API}/${IMG}/${ABI} before create - avdmanager will have nothing to base the AVD on"

AVD_MADE=""
# `--sdk_root` on both tools, because a cmdline-tools `avdmanager` defaults to its *own* parent directory as
# the SDK root, which is not necessarily $ANDROID_HOME: when the image was installed into one root and
# avdmanager looks in another, the answer is a bare "Error: Package path is not valid" and the emulator then
# dies at 0s with no AVD. `--force` because re-running a row on a warm runner must not fail on an existing AVD.
# `avdmanager [global options] create avd [action options]` - a global option after the subcommand is a
# usage dump and a non-zero exit, which is precisely what cost run 38127110617 all four rows: the AVD was
# never created, the emulator exited at 0s, and the "failure" read like a runner-capability problem.
# Three documented invocation forms, tried in one run and each logged by name. Run 7903081 got a usage dump
# from the trailing-option form and run ee4d09f the same from the leading-option form; guessing again would
# cost another 15-minute round trip, so this asks the tool all three questions at once and reports which one
# worked - or exactly how each was rejected.
: > /tmp/avd.log
AVD_MADE=""
AVD_TRIES=""
for form in global-device global-bare trailing; do
  case "$form" in
    # `-b/--abi` in all three: run 30b75c1's usage dump ended on "-b --abi : The ABI to use for the AVD",
    # which is this version telling us it will not always infer it, and `--force` so a re-run on a warm
    # runner is not a failure.
    global-device) cmd=("$AVDMGR" --sdk_root="$SDK" create avd --name "$AVD_NAME" --package "$AVD_PKG" --abi "$ABI" --force --device "$DEVICE") ;;
    global-bare)   cmd=("$AVDMGR" --sdk_root="$SDK" create avd -n "$AVD_NAME" -k "$AVD_PKG" -b "$ABI" --force) ;;
    trailing)      cmd=("$AVDMGR" create avd -n "$AVD_NAME" -k "$AVD_PKG" -b "$ABI" --force -d "$DEVICE" --sdk_root="$SDK") ;;
  esac
  [ -x "$AVDMGR" ] || [ -n "$(command -v "${AVDMGR:-}" 2>/dev/null)" ] || { echo "::error::no avdmanager (searched the SDK's cmdline-tools and PATH) - the hand-written AVD below is the only option"; break; }
  { echo "--- try $form"; echo "${cmd[*]}"; } >> /tmp/avd.log
  # `if ...; then` swallows the status: an `if` with no `else` exits 0 when its condition is false, which is
  # how the first version of this loop recorded every rejected form as `=0`. Capture it from the command.
  echo no | "${cmd[@]}" >> /tmp/avd.log 2>&1 && rc=0 || rc=$?
  if [ "$rc" = 0 ]; then
    AVD_MADE="$form"
    AVD_TRIES="$AVD_TRIES $form=ok"
    echo "::notice::avdmanager accepted the '$form' form (attempts:$AVD_TRIES)"
    break
  fi
  note=$(tr '\r' '\n' < /tmp/avd.log | sed -n "/--- try $form/,\$p" \
         | grep -viE "^[[:space:]]*[-a-z]+ +:|^--- try |^avdmanager |^[[:space:]]*$" | tail -1 | cut -c1-140)
  AVD_TRIES="$AVD_TRIES $form=$rc"
  echo "   try $form -> rc=$rc ${note:-rejected outright (usage dump, no error sentence)}" >> /tmp/avd.log
done
[ -n "$AVD_MADE" ] || echo "::warning::no avdmanager form worked ($AVD_TRIES) - falling back to a hand-written AVD. Last words: $(lastlines /tmp/avd.log)"

# Where the AVD actually lives is decided by the tools' own precedence: ANDROID_AVD_HOME, then
# $ANDROID_SDK_HOME/.android/avd (the deprecated variable is still honoured, and CI images set it), then
# $HOME/.android/avd. Assuming the last one is what turned run 38121489777's four rows red: `avdmanager`
# created the image and reported nothing, and the check looked in the wrong home. So search instead of
# guessing.
AVD_HOME=${ANDROID_AVD_HOME:-}
[ -z "$AVD_HOME" ] && [ -n "${ANDROID_SDK_HOME:-}" ] && AVD_HOME="$ANDROID_SDK_HOME/.android/avd"
[ -z "$AVD_HOME" ] && AVD_HOME="$HOME/.android/avd"
if [ ! -f "$AVD_HOME/$AVD_NAME.avd/config.ini" ]; then
  found=$(find "$SDK" "$HOME/.android" -maxdepth 7 -name config.ini -path "*/$AVD_NAME.avd/*" 2>/dev/null | head -1)
  if [ -n "$found" ]; then
    echo "   AVD found outside the default home: $found"
    AVD_HOME=$(dirname "$(dirname "$found")")
    AVD_MADE=found-it
  fi
fi

if [ -z "$AVD_MADE" ]; then
  echo "   (avdmanager attempts: ${AVD_TRIES:-none})"
  # Last resort, and the one that has rescued more CI emulators than any amount of avdmanager wrangling: an
  # AVD is two text files. The emulator reads them directly and does not care who wrote them, so a broken
  # profile database, a stale devices.xml or a version skew between cmdline-tools and the emulator package
  # stops being a reason the app went untested.
  echo "::warning::writing $AVD_NAME.avd/config.ini by hand - avdmanager is not producing an AVD on this image"
  mkdir -p "$AVD_HOME/$AVD_NAME.avd"
  cat > "$AVD_HOME/$AVD_NAME.ini" <<INI
avd.ini.encoding=UTF-8
path=$AVD_HOME/$AVD_NAME.avd
path.rel=avd/$AVD_NAME.avd
target=android-$API
INI
  cat > "$AVD_HOME/$AVD_NAME.avd/config.ini" <<INI
AvdId=$AVD_NAME
PlayStore.enabled=false
abi.type=$ABI
avd.ini.encoding=UTF-8
hw.arch=x86
Abi=$ABI
hw.cpu.ncore=$CORES
hw.ramSize=$MEMORY
vm.heapSize=256
hw.sdCard=yes
sdcard.size=1024M
hw.gpu.enabled=no
hw.gpu.mode=off
hw.keyboard=yes
hw.audioInput=no
hw.audioOutput=no
hw.camera.back=none
hw.camera.front=none
hw.gps=yes
hw.sensors.orientation=yes
hw.sensors.proximity=yes
disk.dataPartition.size=6G
hw.lcd.density=420
hw.lcd.width=1080
hw.lcd.height=2400
image.sysdir.1=system-images/android-${API}/${IMG}/${ABI}/
systemImage.arch=REPLACED_ARCH
systemImage.tag.id=${IMG}
hw.cpu.arch=REPLACED_ARCH
tag.id=${IMG}
tag.ids=${IMG}
tag.display=CI
target=android-$API
API_LEVEL=$API
IniVersion=2
INI
  # hw.arch has to name the *family*, and the three names do not map onto each other by trimming: an
  # x86_64 image needs hw.arch=x86_64 (writing x86 there is a boot failure, and `${ABI%%_*}` would have done
  # exactly that), arm64-v8a needs arm64, armeabi-v7a needs arm.
  case "$ABI" in
    x86_64) EM_ARCH=x86_64 ;;
    x86) EM_ARCH=x86 ;;
    arm64-v8a) EM_ARCH=arm64 ;;
    armeabi-v7a) EM_ARCH=arm ;;
    *) EM_ARCH=${ABI%%-*} ;;
  esac
  sed -i "s|^hw.arch=.*|hw.arch=$EM_ARCH|" "$AVD_HOME/$AVD_NAME.avd/config.ini"
  sed -i "s|^abi.type=.*|abi.type=$ABI|" "$AVD_HOME/$AVD_NAME.avd/config.ini"
  echo "   hand-written AVD: hw.arch=$EM_ARCH abi.type=$ABI image=$SDK/system-images/android-${API}/${IMG}/${ABI}/"
  AVD_MADE=hand-written
  # "Found AVD target architecture: arm / FATAL CPU Architecture 'arm' is not supported" is how run
  # 38127110617's hand-written AVD died: the emulator takes the architecture from the config, and a key it
  # does not read leaves it guessing from the image directory. Name it in every spelling the tools use, and
  # say what the file ended up containing, because this file is the one thing the reader cannot inspect.
  # The keys in an annotation, because the reader cannot open the file: "Found AVD target architecture: arm"
  # on an x86_64 image is only explicable from the config, and the emulator picks the profile's own default
  # if a device/skin key sends it looking in a database the image may not have. So no device.name, no skin.*
  # - just the display numbers - and every architecture spelling set from $ABI.
  sed -i "s|^systemImage.arch=.*|systemImage.arch=$EM_ARCH|; s|^hw.cpu.arch=.*|hw.cpu.arch=$EM_ARCH|" "$AVD_HOME/$AVD_NAME.avd/config.ini"
  # "Found AVD target architecture: arm" was printed against a config whose hw.arch/abi.type/Abi all said
  # x86_64 (the keys are in the annotation above, verbatim), so the emulator does not take the architecture
  # from those: avdmanager's own config.ini also carries systemImage.arch, which is what the AVD-info code
  # calls the *target* architecture. A hand-written file missing only that key defaults to arm, and QEMU2
  # then refuses - a three-day detour caused by one line. Write both spellings, and print them.
  for k in hw.arch hw.cpu.arch systemImage.arch abi.type Abi image.sysdir.1 tag.id tag.ids systemImage.tag.id target API_LEVEL hw.ramSize hw.cpu.ncore; do
    printf '%s=%s\n' "$k" "$(grep -m1 "^$k=" "$AVD_HOME/$AVD_NAME.avd/config.ini" | cut -d= -f2-)"
  done > /tmp/avd-config-keys.txt
  grep -q '^image.sysdir.1=' "$AVD_HOME/$AVD_NAME.avd/config.ini" || echo "::error::hand-written config.ini has no image.sysdir.1"
  echo "::notice::hand-written AVD keys: $(tr '\n' ' ' < /tmp/avd-config-keys.txt | cut -c1-360)"
fi

if [ ! -d "$SDK/system-images/android-${API}/${IMG}/${ABI}" ]; then
  echo "::error::nothing to boot: $SDK/system-images/android-${API}/${IMG}/${ABI} does not exist (AVD written by $AVD_MADE, so the image install is the problem) - sdkmanager said: $(lastlines /tmp/sdkmanager.log)"
  exit 3
fi
echo "== avd keys: $(tr '\n' ' ' < /tmp/avd-config-keys.txt 2>/dev/null || echo none)"
echo "== avd: home=$AVD_HOME made-by=$AVD_MADE accel=$ACCEL (HOME=$HOME ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-unset} ANDROID_SDK_HOME=${ANDROID_SDK_HOME:-unset})"
[ -f "$AVD_HOME/$AVD_NAME.avd/config.ini" ] || echo "::error::no $AVD_NAME.avd/config.ini in $AVD_HOME even after every fallback - the emulator will not start"
ls -la "$AVD_HOME" 2>/dev/null | head -6 || echo "   (no $AVD_HOME directory)"

# No -read-only: it tells the emulator not to create anything, and an AVD that was assembled by hand has no
# userdata-qemu.img yet - the run would die on "Unable to find a suitable userdata" for a reason that has
# nothing to do with the app. -no-snapshot -no-snapshot-save already keep the run stateless.
EMFLAGS=(-avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot -no-snapshot-save
  -netfast -verbose -cores "$CORES" -memory "$MEMORY")
if [ "$ACCEL" = kvm ]; then
  EMFLAGS+=(-gpu swiftshader_indirect -accel on)
else
  # No GPU at all, and no `-accel` argument to argue with: `-no-accel` is the explicit "software only".
  EMFLAGS+=(-gpu off -no-accel)
fi

adb start-server >/dev/null 2>&1 || true
echo "== emulator ${EMFLAGS[*]}"
nohup "$SDK/emulator/emulator" "${EMFLAGS[@]}" > /tmp/emulator.log 2>&1 &
EM_PID=$!
echo "emulator pid $EM_PID"

# Two-stage wait: first for adb to see *a* device, then for the framework to report boot completion. A
# single `wait-for-device` returns while the screen is still `sh`, and everything after it fails uselessly.
timeout 300 adb wait-for-device || echo "::warning::adb did not report a device within 300 s; continuing to poll"
start=$(date +%s)
while :; do
  now=$(date +%s)
  if [ $((now - start)) -gt "$BOOT_TIMEOUT" ]; then
    echo "::error::emulator did not finish booting in ${BOOT_TIMEOUT}s under accel=$ACCEL (api $API, $IMG/$ABI, AVD by $AVD_MADE). Last lines: $(lastlines /tmp/emulator.log)"
    echo "--- emulator.log (tail) ---"; tail -40 /tmp/emulator.log
    echo "--- ps ---"; ps -o pid,etime,rss,args -p "$EM_PID" 2>&1 | tail -3
    exit 3
  fi
  if kill -0 "$EM_PID" 2>/dev/null; then
    booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    [ "$booted" = "1" ] && break
  else
    echo "::error::the emulator process exited at $((now - start))s (AVD by $AVD_MADE) - $(lastlines /tmp/emulator.log)"
    tail -30 /tmp/emulator.log
    exit 3
  fi
  # A heartbeat, once a minute: "the framework is still coming up" and "nothing has moved for 20 minutes"
  # look the same from the outside, and the difference decides whether to wait, to enlarge the budget, or
  # to conclude the runner cannot host this image.
  if [ $(((now - start) % 60)) -lt 6 ] && [ "$now" != "$start" ]; then
    echo "   [+$((now - start))s] boot_completed=${booted:-?} rss=$(ps -o rss= -p "$EM_PID" 2>/dev/null | tr -d ' ' || echo ?)kB last=$(tr '\r' '\n' < /tmp/emulator.log 2>/dev/null | grep -v '^[[:space:]]*$' | tail -1 | cut -c1-90)"
  fi
  sleep 5
done
echo "== booted in $(( $(date +%s) - start ))s (accel=$ACCEL)"

# Determinism the suite depends on: no animations (they make `Until.idle` and every wait longer than the
# thing being measured), no soft-keyboard autocomplete rewriting typed text, and no lock screen.
adb shell settings put global window_animation_scale 0 >/dev/null
adb shell settings put global transition_animation_scale 0 >/dev/null
adb shell settings put global animator_duration_scale 0 >/dev/null
adb shell settings put secure show_ime_with_hard_keyboard 1 >/dev/null || true
adb shell input keyevent 82 >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb logcat -c || true
adb shell dumpsys dropbox --clear >/dev/null 2>&1 || true
echo "emulator ready: $(adb devices | tail -n +2 | head -1)"
