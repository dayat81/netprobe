# NetProbe

Network quality probe & monitoring platform — Android client, API server, and web dashboards.

## Repository Structure

```
netprobe/
├── android/          # Android UDP client app (Kotlin)
│   ├── app/          # Main application module
│   ├── gradle/       # Gradle wrapper
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── server/           # Backend API server (Python/FastAPI)
│   ├── app.py        # Main FastAPI app
│   ├── db.py         # Database layer
│   ├── udp_probe.py  # UDP probe engine
│   ├── abr_server.py # ABR streaming test
│   ├── max_server.py # Max throughput test
│   ├── webtransport_server.py
│   ├── probe_handler.py
│   ├── udp_probe_protocol.py
│   ├── static/       # Web client JS
│   └── requirements.txt
└── web/              # Web dashboards (HTML/JS)
    ├── index.html    # Dashboard index
    ├── dashboard_*.html
    └── *.json         # Data files
```

## Android Client

Built with Kotlin, WireGuard VPN (Go backend), and OpenTelemetry RUM.

### Build

```bash
cd android
./gradlew assembleRelease
```

APK output: `android/app/build/outputs/apk/release/netprobe-<version>.apk`

### CI

GitHub Actions auto-builds and creates a release on every push to `main`.

## Server

Python/FastAPI backend with UDP probe, ABR streaming, and max throughput tests.

### Run

```bash
cd server
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8000
```

## Web Dashboards

Static HTML dashboards for network quality monitoring (video, gaming, packet loss, etc).

Serve with any static file server:

```bash
cd web
python3 -m http.server 3000
```

## License

MIT