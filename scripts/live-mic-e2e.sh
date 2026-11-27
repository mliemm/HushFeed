#!/bin/bash
# End to end check of the LIVE microphone patch on a real phone with a Bluetooth headset.
#
# An emulator has no Bluetooth headset, so this runs against a phone over adb (USB or wireless
# debugging). It installs the build when given one, waits for TikTok to start recording, then
# reads Android's own audio state: the call mode, the communication device, and the input device
# TikTok's recording is routed to. The last one is the answer; the log lines say why.
#
#   scripts/live-mic-e2e.sh [-s serial] [-i patched.apk] [-t seconds]
#
# Start the LIVE (voice chat or video) with the headset connected once it says "waiting".
# Everything it read is kept in build/live-mic-e2e/<time>/ for a closer look.
set -u
PKG=com.zhiliaoapp.musically
SERIAL="" APK="" WAIT=180
while getopts "s:i:t:" opt; do
    case $opt in
        s) SERIAL=$OPTARG ;;
        i) APK=$OPTARG ;;
        t) WAIT=$OPTARG ;;
        *) exit 2 ;;
    esac
done
ADB=(adb)
[[ -n $SERIAL ]] && ADB=(adb -s "$SERIAL")
sh() { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }

if [[ $("${ADB[@]}" get-state 2>/dev/null) != device ]]; then
    echo "FAIL: no phone over adb. Plug it in or pair wireless debugging, then run again." >&2
    exit 2
fi
OUT="build/live-mic-e2e/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
echo "phone: $(sh getprop ro.product.model), Android $(sh getprop ro.build.version.release)"

if [[ -n $APK ]]; then
    echo "installing $APK"
    "${ADB[@]}" install -r "$APK" >"$OUT/install.txt" 2>&1 || { cat "$OUT/install.txt"; exit 2; }
fi
echo "TikTok: $(sh dumpsys package $PKG | grep -m1 versionName | xargs)"

sh dumpsys audio >"$OUT/audio-before.txt"
if ! grep -qiE 'BLUETOOTH_SCO|BLE_HEADSET|BT_SCO|bt-sco|type:7|type:26' "$OUT/audio-before.txt"; then
    echo "WARN: Android lists no Bluetooth headset for calls. Connect the earbuds first."
fi

"${ADB[@]}" logcat -c
"${ADB[@]}" logcat -v time >"$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
trap 'kill $LOGCAT 2>/dev/null' EXIT

UID_=$(sh cmd package list packages -U $PKG | sed -n 's/.*uid://p' | head -1)
echo "waiting up to ${WAIT}s for TikTok (uid $UID_) to record. Start the LIVE now."
recording=""
for ((i = 0; i < WAIT; i += 2)); do
    if sh dumpsys audio | grep -A40 -i 'recording activity\|RecordActivityMonitor' | grep -qE "uid:?\s*$UID_\b|$PKG"; then
        recording=yes
        break
    fi
    sleep 2
done
# Give the route time to settle: SCO takes a second or two to connect.
sleep 6
sh dumpsys audio >"$OUT/audio-live.txt"
sh dumpsys media.audio_policy >"$OUT/audio-policy-live.txt"
kill $LOGCAT 2>/dev/null
grep -iE 'morphe|LiveMicrophone|AudioRouteDeviceManager|AudioRouteController|BluetoothManager|setAudioRoute|AudioModeHelper' \
    "$OUT/logcat.txt" >"$OUT/route-log.txt"

pass=0 fail=0
check() {
    if [[ $2 == yes ]]; then echo "PASS  $1"; ((pass++)); else echo "FAIL  $1"; ((fail++)); fi
}
mode=$(grep -m1 -iE 'mode *[:=] *(MODE_)?[A-Z_]+|Audio mode' "$OUT/audio-live.txt" | xargs)
commdev=$(grep -iE 'communication (route|device)|mCommunicationRoute|preferred communication' "$OUT/audio-live.txt" | head -3 | xargs)
input=$(grep -iE 'AUDIO_DEVICE_IN_[A-Z_]+' -o "$OUT/audio-policy-live.txt" | sort | uniq -c | sort -rn | head -5 | xargs)

check "TikTok started recording" "${recording:-no}"
check "the patch saw the capture start" "$(grep -q 'LIVE capture started in' "$OUT/route-log.txt" && echo yes)"
check "the patch asked for the headset" "$(grep -q 'Requested LIVE Bluetooth audio device' "$OUT/route-log.txt" && echo yes)"
check "phone is in communication mode" "$(grep -qiE 'IN_COMMUNICATION' <<<"$mode $commdev" && echo yes)"
check "communication device is Bluetooth" "$(grep -qiE 'BLUETOOTH_SCO|BLE_HEADSET|BT_SCO|bt_sco|ble_headset' <<<"$commdev" && echo yes)"
check "an input is routed to the headset" "$(grep -qE 'AUDIO_DEVICE_IN_(BLUETOOTH_SCO_HEADSET|BLE_HEADSET)' "$OUT/audio-policy-live.txt" && echo yes)"

echo
echo "mode:   ${mode:-not found}"
echo "comm:   ${commdev:-not found}"
echo "inputs: ${input:-none}"
echo
echo "last route lines:"
tail -15 "$OUT/route-log.txt"
echo
echo "$pass passed, $fail failed. Dumps and logs: $OUT"
[[ $fail -eq 0 ]]
