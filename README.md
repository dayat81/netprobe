# NetProbe (Android)

Kotlin Android app for cellular radio logging (LTE/NR), CSV sync to [netprobe.xyz](https://netprobe.xyz), and UDP probing to `netprobe.xyz:8765` (latency, loss, jitter).

## Requirements

- JDK 17+
- Android SDK (API 35)
- `ANDROID_HOME` or Android SDK installed via Android Studio

## Build

```bash
cd android/udp-client
./gradlew assembleDebug
```

APK output:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Install

With a device connected over USB debugging:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Open **NetProbe** on the device.
2. Tap **Start**.
3. The app runs 5 rounds (15 packets each) against `netprobe.xyz:8765`.
4. Review per-packet logs and round/overall metrics on screen.

## Default probe settings

| Setting | Value |
|---------|-------|
| Host | `netprobe.xyz` |
| Port | `8765` |
| Rounds | `5` |
| Packets/round | `15` |
| Pause between rounds | `500ms` |
| Receive timeout | `3000ms` |

## Metrics

- **Latency**: RTT per packet (send → reply), with avg/min/max
- **Loss**: packets with no reply before timeout
- **Jitter**: mean absolute difference between consecutive RTT samples (IPDV)

## Manual verification

Compare results with the Python client from the same network:

```bash
python3 scripts/udp_client.py
```

Both should receive `ACK N/15` replies and `OK 15/15 complete` on the final packet of each round.
