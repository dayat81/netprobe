import json
import os
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

import db
from udp_probe import ProbeRunConfig, run_probe
from abr_server import run_abr
from max_server import run_max_test

STATIC_DIR = Path(__file__).resolve().parent / "static"
UPLOAD_KEY = os.environ.get("NETPROBE_UPLOAD_KEY", "")
UDP_PROBE_HOST = os.environ.get("NETPROBE_UDP_HOST", "netprobe.xyz")
UDP_PROBE_PORT = int(os.environ.get("NETPROBE_UDP_PORT", "8765"))


def _client_ip(request: Request) -> str | None:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        return forwarded.split(",")[0].strip()
    real_ip = request.headers.get("x-real-ip")
    if real_ip:
        return real_ip.strip()
    if request.client:
        return request.client.host
    return None


def _lookup_isp_ip(ip: str) -> dict[str, str | bool | None]:
    ip_api_url = (
        f"http://ip-api.com/json/{ip}"
        "?fields=status,message,isp,org,as,query,mobile,proxy,hosting"
    )
    try:
        with urllib.request.urlopen(ip_api_url, timeout=10) as resp:
            data = json.loads(resp.read().decode())
        if data.get("status") == "success":
            return {
                "ip": data.get("query") or ip,
                "isp": (data.get("isp") or "").strip() or None,
                "org": (data.get("org") or "").strip() or None,
                "as": (data.get("as") or "").strip() or None,
                "mobile": bool(data.get("mobile")),
            }
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        pass

    ipwho_url = f"https://ipwho.is/{ip}"
    with urllib.request.urlopen(ipwho_url, timeout=10) as resp:
        data = json.loads(resp.read().decode())
    if not data.get("success"):
        message = data.get("message") or "IP lookup failed"
        raise ValueError(message)

    connection = data.get("connection") or {}
    return {
        "ip": data.get("ip") or ip,
        "isp": (connection.get("isp") or "").strip() or None,
        "org": (connection.get("org") or "").strip() or None,
        "as": f"AS{connection['asn']} {connection.get('org') or ''}".strip()
        if connection.get("asn")
        else None,
        "mobile": None,
    }

app = FastAPI(title="NetProbe", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

WG_INTERFACE = os.environ.get("NETPROBE_WG_INTERFACE", "wg0")
WG_CONFIG_PATH = os.environ.get("NETPROBE_WG_CONFIG", "/etc/wireguard/wg0.conf")
WG_SUBNET = os.environ.get("NETPROBE_WG_SUBNET", "10.10.0")
WG_SERVER_IP = os.environ.get("NETPROBE_WG_SERVER_IP", f"{WG_SUBNET}.1/24")
WG_DNS = os.environ.get("NETPROBE_WG_DNS", "1.1.1.1,8.8.8.8")
WG_ALLOWED_IPS = os.environ.get("NETPROBE_WG_ALLOWED_IPS", "0.0.0.0/1, 128.0.0.0/1")
REGISTER_SECRET = os.environ.get("NETPROBE_REGISTER_SECRET", "")


@app.on_event("startup")
def startup() -> None:
    db.init_db()


@app.get("/")
def dashboard() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/vpn/status")
def vpn_status() -> dict:
    """Check WireGuard VPN server status."""
    import subprocess
    
    result = {
        "installed": False,
        "running": False,
        "interface": None,
        "listen_port": None,
        "public_key": None,
        "peers": [],
        "error": None,
    }
    
    # Check if wg is installed
    try:
        wg_version = subprocess.run(
            ["wg", "--version"],
            capture_output=True, text=True, timeout=5
        )
        if wg_version.returncode == 0:
            result["installed"] = True
            result["version"] = wg_version.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        result["error"] = "WireGuard not installed"
        return result
    
    # Get WireGuard interface info
    try:
        wg_show = subprocess.run(
            ["wg", "show"],
            capture_output=True, text=True, timeout=5
        )
        if wg_show.returncode != 0:
            result["error"] = wg_show.stderr.strip() or "No WireGuard interface found"
            return result
        
        output = wg_show.stdout
        if not output.strip():
            result["error"] = "No WireGuard interface configured"
            return result
        
        result["running"] = True
        
        # Parse wg show output
        current_interface = None
        current_peer = None
        
        for line in output.split("\n"):
            line = line.strip()
            if not line:
                continue
            
            if line.startswith("interface:"):
                current_interface = line.split(":", 1)[1].strip()
                result["interface"] = current_interface
                current_peer = None
            
            elif line.startswith("public key:"):
                if current_peer is None:
                    result["public_key"] = line.split(":", 1)[1].strip()
                else:
                    current_peer["public_key"] = line.split(":", 1)[1].strip()
            
            elif line.startswith("listening port:"):
                result["listen_port"] = int(line.split(":", 1)[1].strip())
            
            elif line.startswith("peer:"):
                current_peer = {
                    "public_key": line.split(":", 1)[1].strip(),
                    "endpoint": None,
                    "allowed_ips": None,
                    "latest_handshake": None,
                    "transfer_rx": 0,
                    "transfer_tx": 0,
                }
                result["peers"].append(current_peer)
            
            elif line.startswith("endpoint:") and current_peer:
                current_peer["endpoint"] = line.split(":", 1)[1].strip()
            
            elif line.startswith("allowed ips:") and current_peer:
                current_peer["allowed_ips"] = line.split(":", 1)[1].strip()
            
            elif line.startswith("latest handshake:") and current_peer:
                current_peer["latest_handshake"] = line.split(":", 1)[1].strip()
            
            elif line.startswith("transfer:") and current_peer:
                transfer = line.split(":", 1)[1].strip()
                parts = transfer.split(",")
                if len(parts) == 2:
                    rx_part = parts[0].strip()
                    tx_part = parts[1].strip()
                    # Parse "received" and "sent"
                    if "received" in rx_part:
                        current_peer["transfer_rx"] = rx_part.replace("received", "").strip()
                    if "sent" in tx_part:
                        current_peer["transfer_tx"] = tx_part.replace("sent", "").strip()
        
        # Count active peers (with recent handshake)
        result["active_peers"] = len([p for p in result["peers"] if p.get("latest_handshake") and "ago" in p["latest_handshake"]])
        result["total_peers"] = len(result["peers"])
        
    except subprocess.TimeoutExpired:
        result["error"] = "Timeout reading WireGuard status"
    except Exception as e:
        result["error"] = str(e)
    
    return result


@app.get("/api/vpn/peers")

@app.get("/api/vpn/traffic/client/{client_ip}")
def vpn_traffic_client(client_ip: str) -> dict:
    """Get traffic stats for a specific client from eBPF monitor (port 8766)."""
    import urllib.request
    import json as _json

    try:
        url = "http://127.0.0.1:8766/api/traffic"
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = _json.loads(resp.read())

        clients = data.get("clients", {})
        if client_ip in clients:
            client = clients[client_ip]
            peer = client.get("peer", {})
            return {
                "peer": {
                    "public_key": peer.get("pubkey", ""),
                    "client_ip": client_ip,
                    "wg_rx": peer.get("wg_rx", 0),
                    "wg_tx": peer.get("wg_tx", 0),
                },
                "total_flows": client.get("total_flows", 0),
                "active_flows": client.get("active_flows", 0),
                "rx_rate": client.get("rx_rate", 0),
                "tx_rate": client.get("tx_rate", 0),
                "protocols": client.get("protocols", {}),
                "top_destinations": client.get("top_destinations", []),
                "top_ports": client.get("top_ports", []),
                "flows_detail": client.get("flows_detail", []),
            }

        # Fallback: wg show directly
        import subprocess
        wg_transfer = subprocess.run(
            ["/usr/bin/wg", "show", "wg0", "transfer"],
            capture_output=True, text=True, timeout=5
        )
        wg_allowed = subprocess.run(
            ["/usr/bin/wg", "show", "wg0", "allowed-ips"],
            capture_output=True, text=True, timeout=5
        )
        peer_ips = {}
        for line in wg_allowed.stdout.split("\n"):
            line = line.strip()
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                peer_ips[parts[0]] = parts[1]
        target_pubkey = None
        for pubkey, ips in peer_ips.items():
            if client_ip in ips:
                target_pubkey = pubkey
                break
        if target_pubkey:
            for line in wg_transfer.stdout.split("\n"):
                parts = line.strip().split("\t")
                if len(parts) >= 3 and parts[0] == target_pubkey:
                    return {
                        "peer": {"public_key": target_pubkey, "client_ip": client_ip,
                                 "wg_rx": int(parts[1]) if parts[1].isdigit() else 0,
                                 "wg_tx": int(parts[2]) if parts[2].isdigit() else 0},
                        "total_flows": 0, "active_flows": 0,
                        "rx_rate": 0, "tx_rate": 0,
                        "protocols": {}, "top_destinations": [], "top_ports": [],
                    }
        return {"error": f"Client {client_ip} not found", "peer": None}
    except Exception as e:
        return {"error": str(e), "peer": None}

def vpn_peers() -> dict:
    """Get WireGuard peer statistics."""
    import subprocess
    
    try:
        wg_show = subprocess.run(
            ["wg", "show", "all", "transfer"],
            capture_output=True, text=True, timeout=5
        )
        if wg_show.returncode != 0:
            return {"peers": [], "error": wg_show.stderr.strip()}
        
        peers = []
        current_interface = None
        current_peer = None
        
        for line in wg_show.stdout.split("\n"):
            line = line.strip()
            if not line:
                continue
            
            parts = line.split("	")
            if len(parts) >= 2:
                if parts[0] != current_interface:
                    current_interface = parts[0]
                
                if len(parts) == 4:  # interface, public_key, rx_bytes, tx_bytes
                    peers.append({
                        "interface": current_interface,
                        "public_key": parts[1],
                        "rx_bytes": int(parts[2]) if parts[2].isdigit() else 0,
                        "tx_bytes": int(parts[3]) if parts[3].isdigit() else 0,
                    })
        
        return {"peers": peers}
    
    except Exception as e:
        return {"peers": [], "error": str(e)}


def _get_server_public_key() -> str | None:
    """Get WireGuard server public key."""
    try:
        result = subprocess.run(
            ["/usr/bin/sudo", "/usr/bin/wg", "show", WG_INTERFACE, "public-key"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except Exception:
        pass
    return None


def _get_assigned_ips() -> set[str]:
    """Get all currently assigned IPs in the WireGuard subnet."""
    assigned = set()
    try:
        result = subprocess.run(
            ["/usr/bin/sudo", "/usr/bin/wg", "show", WG_INTERFACE, "allowed-ips"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0:
            for line in result.stdout.strip().split("\n"):
                if line.strip():
                    parts = line.split()
                    for ip_cidr in parts[1:]:
                        ip = ip_cidr.split("/")[0]
                        assigned.add(ip)
    except Exception:
        pass
    return assigned


def _find_next_ip() -> str | None:
    """Find next available IP in the 10.10.0.0/24 subnet (skip .1 = server)."""
    assigned = _get_assigned_ips()
    for i in range(2, 255):
        candidate = f"{WG_SUBNET}.{i}"
        if candidate not in assigned:
            return candidate
    return None


def _add_peer_to_wg(pubkey: str, allowed_ip: str) -> bool:
    """Add a peer to WireGuard interface using wg set (requires sudo)."""
    try:
        cmd = ["/usr/bin/sudo", "/usr/bin/wg", "set", WG_INTERFACE, "peer", pubkey, "allowed-ips", f"{allowed_ip}/32"]
        result = subprocess.run(
            cmd,
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode != 0:
            print(f"wg set failed: rc={result.returncode} stderr={result.stderr}")
        return result.returncode == 0
    except Exception as e:
        print(f"wg set exception: {e}")
        return False


def _append_peer_to_config(pubkey: str, allowed_ip: str) -> bool:
    """Append peer to WireGuard config file for persistence across restarts."""
    peer_block = f"\n[Peer]\nPublicKey = {pubkey}\nAllowedIPs = {allowed_ip}/32\n"
    try:
        # Use sudo to append to config file
        import shlex
        result = subprocess.run(
            ["/usr/bin/sudo", "/bin/bash", "-c", f"echo {shlex.quote(peer_block)} >> {WG_CONFIG_PATH}"],
            capture_output=True, text=True, timeout=5,
        )
        return result.returncode == 0
    except Exception:
        return False


class VpnRegisterRequest(BaseModel):
    public_key: str | None = Field(default=None, min_length=40, max_length=60)
    device_name: str | None = Field(default=None, max_length=100)
    secret: str | None = Field(default=None, max_length=200)


@app.post("/api/vpn/register")
def vpn_register(body: VpnRegisterRequest) -> dict:
    """Register a new WireGuard client peer.
    
    Accepts client's public key, assigns next available IP,
    adds peer to WireGuard, and returns connection config.
    """
    if REGISTER_SECRET and body.secret != REGISTER_SECRET:
        raise HTTPException(status_code=401, detail="Invalid registration secret")

    # Generate key pair if public_key not provided
    import base64
    server_generated_key = False
    if body.public_key is None:
        import secrets as _secrets
        priv_bytes = bytearray(_secrets.token_bytes(32))
        priv_bytes[0] &= 248
        priv_bytes[31] &= 127
        priv_bytes[31] |= 64
        priv_b64 = base64.b64encode(bytes(priv_bytes)).decode()
        # wg pubkey expects base64 input on stdin
        pub_result = subprocess.run(
            ["/usr/bin/wg", "pubkey"],
            input=priv_b64.encode(),
            capture_output=True, timeout=5,
        )
        if pub_result.returncode != 0:
            raise HTTPException(status_code=500, detail="Key generation failed")
        pubkey = pub_result.stdout.decode().strip()
        server_generated_key = True
    else:
        pubkey = body.public_key.strip()
        if len(pubkey) < 40 or len(pubkey) > 60:
            raise HTTPException(status_code=400, detail="Invalid public key format")

    # Check if peer already exists
    existing_ips = _get_assigned_ips()
    try:
        result = subprocess.run(
            ["/usr/bin/sudo", "/usr/bin/wg", "show", WG_INTERFACE, "peers"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0 and pubkey in result.stdout:
            # Peer exists, find its IP
            allowed_result = subprocess.run(
                ["/usr/bin/sudo", "/usr/bin/wg", "show", WG_INTERFACE, "allowed-ips"],
                capture_output=True, text=True, timeout=5,
            )
            for line in allowed_result.stdout.strip().split("\n"):
                if pubkey in line:
                    parts = line.split()
                    if len(parts) >= 2:
                        assigned_ip = parts[1].split("/")[0]
                        server_pubkey = _get_server_public_key()
                        return {
                            "status": "already_registered",
                            "assigned_ip": assigned_ip,
                            "server_public_key": server_pubkey,
                            "server_endpoint": "netprobe.xyz",
                            "server_port": 51820,
                            "dns": WG_DNS,
                            "allowed_ips": WG_ALLOWED_IPS,
                        }
    except Exception:
        pass

    # Find next available IP
    next_ip = _find_next_ip()
    if not next_ip:
        raise HTTPException(status_code=503, detail="No available IPs in subnet")

    # Add peer to WireGuard
    if not _add_peer_to_wg(pubkey, next_ip):
        raise HTTPException(status_code=500, detail="Failed to add peer to WireGuard")

    # Persist to config file
    _append_peer_to_config(pubkey, next_ip)

    server_pubkey = _get_server_public_key()
    if not server_pubkey:
        raise HTTPException(status_code=500, detail="Could not read server public key")

    result = {
        "status": "registered",
        "assigned_ip": next_ip,
        "server_public_key": server_pubkey,
        "server_endpoint": "netprobe.xyz",
        "server_port": 51820,
        "dns": WG_DNS,
        "allowed_ips": WG_ALLOWED_IPS,
    }
    if server_generated_key:
        result["private_key"] = priv_b64
        result["client_public_key"] = pubkey
    return result


class ProbeRunRequest(BaseModel):
    rounds: int = Field(default=5, ge=1, le=10)
    packets: int = Field(default=15, ge=1, le=30)
    apply_offset_correction: bool = True
    host: str | None = None
    port: int | None = Field(default=None, ge=1, le=65535)


@app.post("/api/probe/run")
def api_probe_run(body: ProbeRunRequest) -> dict:
    config = ProbeRunConfig(
        host=body.host or UDP_PROBE_HOST,
        port=body.port or UDP_PROBE_PORT,
        rounds=body.rounds,
        packets=body.packets,
        apply_offset_correction=body.apply_offset_correction,
    )
    try:
        return run_probe(config)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except OSError as exc:
        raise HTTPException(status_code=502, detail=f"UDP probe failed: {exc}") from exc


class AbrRunRequest(BaseModel):
    host: str | None = None
    port: int | None = Field(default=None, ge=1, le=65535)
    sizes: list[int] | None = None
    packets_per_round: int = Field(default=10, ge=1, le=20)
    rounds: int = Field(default=5, ge=1, le=10)


@app.post("/api/abr/run")
def api_abr_run(body: AbrRunRequest) -> dict:
    try:
        return run_abr(
            host=body.host or UDP_PROBE_HOST,
            port=body.port or UDP_PROBE_PORT,
            sizes=body.sizes,
            packets_per_round=body.packets_per_round,
            rounds=body.rounds,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except OSError as exc:
        raise HTTPException(status_code=502, detail=f"ABR test failed: {exc}") from exc


@app.get("/api/abr/latest")
def api_abr_latest() -> dict | None:
    return db.get_latest_abr()


@app.get("/api/abr/history")
def api_abr_history(limit: int = 20) -> list[dict]:
    return db.list_abr_results(limit)


class MaxRunRequest(BaseModel):
    host: str | None = None
    port: int | None = None
    packet_size: int = 1400
    stream_counts: list[int] | None = None
    packets_per_stream: int = 50
    burst_duration_s: float = 5.0


@app.post("/api/max/run")
def api_max_run(body: MaxRunRequest) -> dict:
    try:
        return run_max_test(
            host=body.host or UDP_PROBE_HOST,
            port=body.port or UDP_PROBE_PORT,
            packet_size=body.packet_size,
            stream_counts=body.stream_counts,
            packets_per_stream=body.packets_per_stream,
            burst_duration_s=body.burst_duration_s,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except OSError as exc:
        raise HTTPException(status_code=502, detail=f"MAX test failed: {exc}") from exc


@app.get("/api/isp/me")
def api_isp_me(request: Request) -> dict:
    client_ip = _client_ip(request)
    if not client_ip:
        raise HTTPException(status_code=400, detail="Could not determine client IP")

    try:
        data = _lookup_isp_ip(client_ip)
    except ValueError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=502, detail=f"ISP lookup failed: {exc}") from exc

    isp = data.get("isp")
    org = data.get("org")
    return {
        "ip": data.get("ip") or client_ip,
        "isp": isp,
        "org": org,
        "as": data.get("as"),
        "mobile": data.get("mobile"),
        "operator": isp or org,
    }


@app.get("/api/sessions")
def api_sessions(limit: int = 100) -> dict:
    return {"sessions": db.list_sessions(limit=limit)}


@app.get("/api/sessions/nearby")
def api_sessions_nearby(lat: float, lon: float, radius_km: float = 5.0) -> dict:
    if radius_km <= 0 or radius_km > 50:
        raise HTTPException(status_code=400, detail="radius_km must be between 0 and 50")
    if lat < -90 or lat > 90 or lon < -180 or lon > 180:
        raise HTTPException(status_code=400, detail="Invalid lat/lon")
    providers = db.nearby_providers(lat=lat, lon=lon, radius_km=radius_km)
    latest = db.latest_nearby_probe(lat=lat, lon=lon, radius_km=radius_km)
    return {
        "query": {"lat": lat, "lon": lon, "radius_km": radius_km},
        "providers": providers,
        "latest": latest,
    }


@app.get("/api/sessions/nearby/sessions")
def api_nearby_provider_sessions(
    lat: float,
    lon: float,
    operator: str,
    radius_km: float = 5.0,
    limit: int = 10,
) -> dict:
    if radius_km <= 0 or radius_km > 50:
        raise HTTPException(status_code=400, detail="radius_km must be between 0 and 50")
    if lat < -90 or lat > 90 or lon < -180 or lon > 180:
        raise HTTPException(status_code=400, detail="Invalid lat/lon")
    if not operator.strip():
        raise HTTPException(status_code=400, detail="operator is required")
    if limit <= 0 or limit > 50:
        raise HTTPException(status_code=400, detail="limit must be between 1 and 50")
    sessions = db.nearby_provider_sessions(
        lat=lat,
        lon=lon,
        operator=operator,
        radius_km=radius_km,
        limit=limit,
    )
    return {
        "query": {
            "lat": lat,
            "lon": lon,
            "radius_km": radius_km,
            "operator": operator.strip(),
            "limit": limit,
        },
        "sessions": sessions,
    }


@app.get("/api/sessions/{session_id}")
def api_session(session_id: int) -> dict:
    session = db.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    stats = db.session_stats(session_id)
    return {"session": session, "stats": stats}


@app.get("/api/sessions/{session_id}/rows")
def api_session_rows(session_id: int, limit: int = 10000) -> dict:
    if not db.get_session(session_id):
        raise HTTPException(status_code=404, detail="Session not found")
    return {"rows": db.get_session_rows(session_id, limit=limit)}


@app.post("/api/logs/upload")
async def upload_log(
    file: UploadFile = File(...),
    device_id: str | None = Form(default=None),
    app_version: str | None = Form(default=None),
    api_key: str | None = Form(default=None),
) -> dict:
    if UPLOAD_KEY and api_key != UPLOAD_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")

    if not file.filename or not file.filename.lower().endswith(".csv"):
        raise HTTPException(status_code=400, detail="CSV file required")

    content = await file.read()
    if not content:
        raise HTTPException(status_code=400, detail="Empty file")

    try:
        result = db.import_csv(content, file.filename, device_id, app_version)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Import failed: {exc}") from exc

    return result
