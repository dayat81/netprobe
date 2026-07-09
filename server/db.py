import csv
import io
import math
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

OPERATOR_NAMES = {
    "51010": "Telkomsel",
    "51011": "XL Axiata",
    "51001": "Indosat Ooredoo",
    "51021": "Indosat",
    "51089": "Smartfren",
    "51009": "Smartfren",
    "51028": "Ceria",
}

DATA_DIR = Path(__file__).resolve().parent / "data"
DB_PATH = DATA_DIR / "netprobe.db"
UPLOAD_DIR = DATA_DIR / "uploads"

CSV_COLUMNS = [
    "timestamp", "lat", "lon", "imsi", "imei", "device_id", "operator",
    "tech", "tac", "site_id", "cell_id",
    "arfcn", "rsrp", "rsrq", "snr", "nr_pci", "nr_gnb", "nr_cell_id", "nr_arfcn",
    "nr_rsrp", "nr_rsrq", "nr_snr",
    "local_ip", "dns_servers", "network_type", "udp_latency_ms", "udp_loss_pct", "udp_jitter_ms",
    "udp_uplink_ms", "udp_downlink_ms", "udp_uplink_loss_pct", "udp_downlink_loss_pct",
]


def init_db() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DB_PATH) as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                filename TEXT NOT NULL,
                device_id TEXT,
                app_version TEXT,
                uploaded_at TEXT NOT NULL,
                row_count INTEGER NOT NULL DEFAULT 0,
                tech_summary TEXT,
                min_lat REAL,
                max_lat REAL,
                min_lon REAL,
                max_lon REAL
            );

            CREATE TABLE IF NOT EXISTS log_rows (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                timestamp TEXT,
                lat REAL,
                lon REAL,
                imsi TEXT,
                imei TEXT,
                device_id TEXT,
                operator TEXT,
                tech TEXT,
                tac TEXT,
                site_id TEXT,
                cell_id TEXT,
                arfcn TEXT,
                rsrp REAL,
                rsrq REAL,
                snr REAL,
                nr_pci TEXT,
                nr_gnb TEXT,
                nr_cell_id TEXT,
                nr_arfcn TEXT,
                nr_rsrp REAL,
                nr_rsrq REAL,
                nr_snr REAL,
                local_ip TEXT,
                dns_servers TEXT,
                network_type TEXT,
                udp_latency_ms REAL,
                udp_loss_pct REAL,
                udp_jitter_ms REAL,
                FOREIGN KEY (session_id) REFERENCES sessions(id)
            );

            CREATE INDEX IF NOT EXISTS idx_log_rows_session ON log_rows(session_id);
            """
        )
        _migrate_log_rows(conn)


def _migrate_log_rows(conn: sqlite3.Connection) -> None:
    columns = {row[1] for row in conn.execute("PRAGMA table_info(log_rows)")}
    if "device_id" not in columns:
        conn.execute("ALTER TABLE log_rows ADD COLUMN device_id TEXT")
    if "operator" not in columns:
        conn.execute("ALTER TABLE log_rows ADD COLUMN operator TEXT")
    for col, col_type in (
        ("local_ip", "TEXT"),
        ("dns_servers", "TEXT"),
        ("network_type", "TEXT"),
        ("udp_latency_ms", "REAL"),
        ("udp_loss_pct", "REAL"),
        ("udp_jitter_ms", "REAL"),
        ("udp_uplink_ms", "REAL"),
        ("udp_downlink_ms", "REAL"),
        ("udp_uplink_loss_pct", "REAL"),
        ("udp_downlink_loss_pct", "REAL"),
    ):
        if col not in columns:
            conn.execute(f"ALTER TABLE log_rows ADD COLUMN {col} {col_type}")


def _parse_float(value: str) -> float | None:
    value = (value or "").strip()
    if not value:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def _connection_label(
    tech: str | None,
    network_type: str | None,
    tech_summary: str | None = None,
) -> str | None:
    transport = (network_type or "").strip().upper()
    if transport == "WIFI":
        return "WiFi"
    if transport == "ETHERNET":
        return "Ethernet"
    if (tech or "").strip().upper() == "WEB-WT":
        return "WEB-WT"
    if transport == "CELLULAR":
        return tech_summary or tech or "CELLULAR"
    if tech_summary:
        return tech_summary
    if tech:
        return tech
    if network_type:
        return network_type
    return None


def _session_tech_summary(rows: list[dict[str, Any]]) -> str | None:
    if not rows:
        return None
    network_types = {
        str(row.get("network_type") or "").strip().upper()
        for row in rows
        if str(row.get("network_type") or "").strip()
    }
    if network_types == {"WIFI"}:
        return "WiFi"
    if network_types == {"ETHERNET"}:
        return "Ethernet"
    if (rows[0].get("tech") or "").strip().upper() == "WEB-WT":
        return "WEB-WT"
    techs = {str(row["tech"]).strip() for row in rows if str(row.get("tech") or "").strip()}
    return ",".join(sorted(techs)) if techs else None


def import_csv(
    content: bytes,
    filename: str,
    device_id: str | None,
    app_version: str | None,
) -> dict[str, Any]:
    text = content.decode("utf-8-sig", errors="replace")
    reader = csv.DictReader(io.StringIO(text))
    rows: list[dict[str, Any]] = []
    lats: list[float] = []
    lons: list[float] = []

    for raw in reader:
        row = {col: (raw.get(col) or "").strip() for col in CSV_COLUMNS}
        lat = _parse_float(row["lat"])
        lon = _parse_float(row["lon"])
        if lat is not None:
            lats.append(lat)
        if lon is not None:
            lons.append(lon)
        rows.append(
            {
                **row,
                "lat": lat,
                "lon": lon,
                "rsrp": _parse_float(row["rsrp"]),
                "rsrq": _parse_float(row["rsrq"]),
                "snr": _parse_float(row["snr"]),
                "nr_rsrp": _parse_float(row["nr_rsrp"]),
                "nr_rsrq": _parse_float(row["nr_rsrq"]),
                "nr_snr": _parse_float(row["nr_snr"]),
                "udp_latency_ms": _parse_float(row["udp_latency_ms"]),
                "udp_loss_pct": _parse_float(row["udp_loss_pct"]),
                "udp_jitter_ms": _parse_float(row["udp_jitter_ms"]),
                "udp_uplink_ms": _parse_float(row["udp_uplink_ms"]),
                "udp_downlink_ms": _parse_float(row["udp_downlink_ms"]),
                "udp_uplink_loss_pct": _parse_float(row["udp_uplink_loss_pct"]),
                "udp_downlink_loss_pct": _parse_float(row["udp_downlink_loss_pct"]),
            }
        )

    uploaded_at = datetime.now(timezone.utc).isoformat()
    safe_name = Path(filename).name
    stored_path = UPLOAD_DIR / f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')}_{safe_name}"
    stored_path.write_bytes(content)

    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        cur = conn.execute(
            """
            INSERT INTO sessions
                (filename, device_id, app_version, uploaded_at, row_count, tech_summary,
                 min_lat, max_lat, min_lon, max_lon)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                safe_name,
                device_id,
                app_version,
                uploaded_at,
                len(rows),
                _session_tech_summary(rows),
                min(lats) if lats else None,
                max(lats) if lats else None,
                min(lons) if lons else None,
                max(lons) if lons else None,
            ),
        )
        session_id = cur.lastrowid
        conn.executemany(
            """
            INSERT INTO log_rows (
                session_id, timestamp, lat, lon, imsi, imei, device_id, operator,
                tech, tac, site_id, cell_id,
                arfcn, rsrp, rsrq, snr, nr_pci, nr_gnb, nr_cell_id, nr_arfcn,
                nr_rsrp, nr_rsrq, nr_snr,
                local_ip, dns_servers, network_type, udp_latency_ms, udp_loss_pct, udp_jitter_ms,
                udp_uplink_ms, udp_downlink_ms, udp_uplink_loss_pct, udp_downlink_loss_pct
            ) VALUES (
                :session_id, :timestamp, :lat, :lon, :imsi, :imei, :device_id, :operator,
                :tech, :tac, :site_id,
                :cell_id, :arfcn, :rsrp, :rsrq, :snr, :nr_pci, :nr_gnb, :nr_cell_id,
                :nr_arfcn, :nr_rsrp, :nr_rsrq, :nr_snr,
                :local_ip, :dns_servers, :network_type, :udp_latency_ms, :udp_loss_pct, :udp_jitter_ms,
                :udp_uplink_ms, :udp_downlink_ms, :udp_uplink_loss_pct, :udp_downlink_loss_pct
            )
            """,
            [{**row, "session_id": session_id} for row in rows],
        )

    return {
        "session_id": session_id,
        "filename": safe_name,
        "row_count": len(rows),
        "tech_summary": _session_tech_summary(rows),
        "uploaded_at": uploaded_at,
    }


def list_sessions(limit: int = 100) -> list[dict[str, Any]]:
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT id, filename, device_id, app_version, uploaded_at, row_count,
                   tech_summary, min_lat, max_lat, min_lon, max_lon
            FROM sessions
            ORDER BY id DESC
            LIMIT ?
            """,
            (limit,),
        ).fetchall()
    return [dict(row) for row in rows]


def get_session(session_id: int) -> dict[str, Any] | None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        row = conn.execute(
            "SELECT * FROM sessions WHERE id = ?",
            (session_id,),
        ).fetchone()
    return dict(row) if row else None


def get_session_rows(session_id: int, limit: int = 10000) -> list[dict[str, Any]]:
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT timestamp, lat, lon, imsi, imei, device_id, operator, tech, tac, site_id, cell_id, arfcn,
                   rsrp, rsrq, snr, nr_pci, nr_gnb, nr_cell_id, nr_arfcn,
                   nr_rsrp, nr_rsrq, nr_snr,
                   local_ip, dns_servers, network_type, udp_latency_ms, udp_loss_pct, udp_jitter_ms,
                   udp_uplink_ms, udp_downlink_ms, udp_uplink_loss_pct, udp_downlink_loss_pct
            FROM log_rows
            WHERE session_id = ?
            ORDER BY id ASC
            LIMIT ?
            """,
            (session_id, limit),
        ).fetchall()
    return [dict(row) for row in rows]


def _bbox(lat: float, lon: float, radius_km: float) -> tuple[float, float, float, float]:
    lat_delta = radius_km / 111.0
    lon_scale = max(math.cos(math.radians(lat)), 0.01)
    lon_delta = radius_km / (111.0 * lon_scale)
    return lat - lat_delta, lat + lat_delta, lon - lon_delta, lon + lon_delta


def _distance_km(lat: float, lon: float, point_lat: float, point_lon: float) -> float:
    lat_rad = math.radians(lat)
    lon_rad = math.radians(lon)
    point_lat_rad = math.radians(point_lat)
    point_lon_rad = math.radians(point_lon)
    cos_lat = math.cos(lat_rad)
    return 6371.0 * math.acos(
        min(
            1.0,
            max(
                -1.0,
                math.sin(lat_rad) * math.sin(point_lat_rad)
                + cos_lat * math.cos(point_lat_rad) * math.cos(point_lon_rad - lon_rad),
            ),
        )
    )


def latest_nearby_probe(lat: float, lon: float, radius_km: float = 5.0) -> dict[str, Any] | None:
    min_lat, max_lat, min_lon, max_lon = _bbox(lat, lon, radius_km)
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT
                s.id AS session_id,
                s.filename,
                s.device_id,
                s.app_version,
                s.uploaded_at,
                s.tech_summary,
                lr.operator AS operator,
                lr.tech AS tech,
                lr.lat AS lat,
                lr.lon AS lon,
                lr.network_type AS network_type,
                lr.tac AS tac,
                lr.site_id AS site_id,
                lr.rsrp AS rsrp,
                lr.udp_latency_ms AS udp_latency_ms,
                lr.udp_uplink_ms AS udp_uplink_ms,
                lr.udp_downlink_ms AS udp_downlink_ms,
                lr.udp_loss_pct AS udp_loss_pct,
                lr.udp_jitter_ms AS udp_jitter_ms,
                lr.udp_uplink_loss_pct AS udp_uplink_loss_pct,
                lr.udp_downlink_loss_pct AS udp_downlink_loss_pct
            FROM sessions s
            JOIN log_rows lr ON lr.session_id = s.id
            WHERE s.filename LIKE 'probe%'
              AND lr.lat IS NOT NULL
              AND lr.lon IS NOT NULL
              AND lr.lat BETWEEN ? AND ?
              AND lr.lon BETWEEN ? AND ?
            ORDER BY s.uploaded_at DESC, lr.id ASC
            LIMIT 100
            """,
            (min_lat, max_lat, min_lon, max_lon),
        ).fetchall()

    seen_sessions: set[int] = set()
    for row in rows:
        session_id = row["session_id"]
        if session_id in seen_sessions:
            continue
        seen_sessions.add(session_id)
        distance_km = _distance_km(lat, lon, row["lat"], row["lon"])
        if distance_km > radius_km:
            continue
        site_id = row["site_id"] or ""
        failed = site_id.startswith("failed:")
        return {
            "session_id": session_id,
            "filename": row["filename"],
            "device_id": row["device_id"],
            "app_version": row["app_version"],
            "uploaded_at": row["uploaded_at"],
            "tech_summary": row["tech_summary"],
            "operator": row["operator"],
            "operator_name": OPERATOR_NAMES.get(row["operator"] or ""),
            "tech": row["tech"],
            "lat": row["lat"],
            "lon": row["lon"],
            "network_type": row["network_type"],
            "connection_label": _connection_label(
                row["tech"],
                row["network_type"],
                row["tech_summary"],
            ),
            "config": row["tac"],
            "mode": row["site_id"],
            "rsrp": row["rsrp"],
            "distance_km": round(distance_km, 2),
            "failed": failed,
            "failure_message": site_id[7:].strip() if failed else None,
            "udp_latency_ms": row["udp_latency_ms"],
            "udp_uplink_ms": row["udp_uplink_ms"],
            "udp_downlink_ms": row["udp_downlink_ms"],
            "udp_loss_pct": row["udp_loss_pct"],
            "udp_jitter_ms": row["udp_jitter_ms"],
            "udp_uplink_loss_pct": row["udp_uplink_loss_pct"],
            "udp_downlink_loss_pct": row["udp_downlink_loss_pct"],
        }
    return None


def nearby_provider_sessions(
    lat: float,
    lon: float,
    operator: str,
    radius_km: float = 5.0,
    limit: int = 10,
) -> list[dict[str, Any]]:
    min_lat, max_lat, min_lon, max_lon = _bbox(lat, lon, radius_km)
    operator = operator.strip()
    if not operator:
        return []

    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT
                s.id AS session_id,
                s.filename,
                s.device_id,
                s.app_version,
                s.uploaded_at,
                s.tech_summary,
                lr.operator AS operator,
                lr.tech AS tech,
                lr.lat AS lat,
                lr.lon AS lon,
                lr.network_type AS network_type,
                lr.tac AS tac,
                lr.site_id AS site_id,
                lr.rsrp AS rsrp,
                lr.udp_latency_ms AS udp_latency_ms,
                lr.udp_uplink_ms AS udp_uplink_ms,
                lr.udp_downlink_ms AS udp_downlink_ms,
                lr.udp_loss_pct AS udp_loss_pct,
                lr.udp_jitter_ms AS udp_jitter_ms
            FROM sessions s
            JOIN log_rows lr ON lr.session_id = s.id
            WHERE s.filename LIKE 'probe%'
              AND lr.operator = ?
              AND lr.lat IS NOT NULL
              AND lr.lon IS NOT NULL
              AND lr.lat BETWEEN ? AND ?
              AND lr.lon BETWEEN ? AND ?
            ORDER BY s.uploaded_at DESC, lr.id ASC
            LIMIT ?
            """,
            (operator, min_lat, max_lat, min_lon, max_lon, max(1, min(limit, 50)) * 5),
        ).fetchall()

    sessions: list[dict[str, Any]] = []
    seen_sessions: set[int] = set()
    for row in rows:
        session_id = row["session_id"]
        if session_id in seen_sessions:
            continue
        distance_km = _distance_km(lat, lon, row["lat"], row["lon"])
        if distance_km > radius_km:
            continue
        seen_sessions.add(session_id)
        site_id = row["site_id"] or ""
        failed = site_id.startswith("failed:")
        sessions.append(
            {
                "session_id": session_id,
                "filename": row["filename"],
                "device_id": row["device_id"],
                "app_version": row["app_version"],
                "uploaded_at": row["uploaded_at"],
                "tech_summary": row["tech_summary"],
                "operator": row["operator"],
                "operator_name": OPERATOR_NAMES.get(row["operator"] or ""),
                "tech": row["tech"],
                "network_type": row["network_type"],
                "connection_label": _connection_label(
                    row["tech"],
                    row["network_type"],
                    row["tech_summary"],
                ),
                "config": row["tac"],
                "mode": row["site_id"],
                "rsrp": row["rsrp"],
                "distance_km": round(distance_km, 2),
                "failed": failed,
                "failure_message": site_id[7:].strip() if failed else None,
                "udp_latency_ms": row["udp_latency_ms"],
                "udp_uplink_ms": row["udp_uplink_ms"],
                "udp_downlink_ms": row["udp_downlink_ms"],
                "udp_loss_pct": row["udp_loss_pct"],
                "udp_jitter_ms": row["udp_jitter_ms"],
            }
        )
        if len(sessions) >= limit:
            break
    return sessions


def nearby_providers(lat: float, lon: float, radius_km: float = 5.0) -> list[dict[str, Any]]:
    """Aggregate probe providers near a point.

    Card metrics come from each operator's latest probe session (not historical averages).
    ``sample_count`` / ``session_count`` still reflect all probe rows in range for context.
    """
    min_lat, max_lat, min_lon, max_lon = _bbox(lat, lon, radius_km)

    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        rows = conn.execute(
            """
            SELECT
                lr.operator AS operator,
                lr.session_id AS session_id,
                lr.lat AS lat,
                lr.lon AS lon,
                lr.udp_latency_ms AS udp_latency_ms,
                lr.udp_uplink_ms AS udp_uplink_ms,
                lr.udp_downlink_ms AS udp_downlink_ms,
                lr.udp_loss_pct AS udp_loss_pct,
                lr.udp_jitter_ms AS udp_jitter_ms,
                lr.rsrp AS rsrp,
                s.uploaded_at AS uploaded_at
            FROM sessions s
            JOIN log_rows lr ON lr.session_id = s.id
            WHERE s.filename LIKE 'probe%'
              AND lr.lat IS NOT NULL
              AND lr.lon IS NOT NULL
              AND lr.operator IS NOT NULL
              AND TRIM(lr.operator) != ''
              AND lr.udp_latency_ms IS NOT NULL
              AND lr.lat BETWEEN ? AND ?
              AND lr.lon BETWEEN ? AND ?
            ORDER BY s.uploaded_at DESC, lr.id ASC
            """,
            (min_lat, max_lat, min_lon, max_lon),
        ).fetchall()

    grouped: dict[str, dict[str, Any]] = {}
    for row in rows:
        point_lat = row["lat"]
        point_lon = row["lon"]
        distance_km = _distance_km(lat, lon, point_lat, point_lon)
        if distance_km > radius_km:
            continue

        operator = row["operator"]
        bucket = grouped.setdefault(
            operator,
            {
                "operator": operator,
                "sample_count": 0,
                "session_ids": set(),
                "nearest_km": distance_km,
                "last_seen": row["uploaded_at"],
                "latest": None,
            },
        )
        bucket["sample_count"] += 1
        bucket["session_ids"].add(row["session_id"])
        bucket["nearest_km"] = min(bucket["nearest_km"], distance_km)
        if row["uploaded_at"] and row["uploaded_at"] > bucket["last_seen"]:
            bucket["last_seen"] = row["uploaded_at"]

        # Rows are newest-first; first row per operator is the latest session's probe point.
        if bucket["latest"] is None:
            bucket["latest"] = {
                "session_id": row["session_id"],
                "uploaded_at": row["uploaded_at"],
                "distance_km": distance_km,
                "udp_latency_ms": row["udp_latency_ms"],
                "udp_uplink_ms": row["udp_uplink_ms"],
                "udp_downlink_ms": row["udp_downlink_ms"],
                "udp_loss_pct": row["udp_loss_pct"],
                "udp_jitter_ms": row["udp_jitter_ms"],
                "rsrp": row["rsrp"],
            }

    providers: list[dict[str, Any]] = []
    for bucket in grouped.values():
        latest = bucket["latest"]
        if latest is None:
            continue
        providers.append(
            {
                "operator": bucket["operator"],
                "operator_name": OPERATOR_NAMES.get(bucket["operator"]),
                "sample_count": bucket["sample_count"],
                "session_count": len(bucket["session_ids"]),
                # Field names kept for API compatibility; values are from the latest session.
                "avg_udp_latency_ms": latest["udp_latency_ms"],
                "avg_udp_uplink_ms": latest["udp_uplink_ms"],
                "avg_udp_downlink_ms": latest["udp_downlink_ms"],
                "avg_udp_loss_pct": latest["udp_loss_pct"],
                "avg_udp_jitter_ms": latest["udp_jitter_ms"],
                "avg_rsrp": latest["rsrp"],
                "nearest_km": round(latest["distance_km"], 2),
                "last_seen": latest["uploaded_at"],
                "latest_session_id": latest["session_id"],
            }
        )

    providers.sort(
        key=lambda item: (
            item["last_seen"] or "",
            -(item["nearest_km"] if item["nearest_km"] is not None else 9999.0),
        ),
        reverse=True,
    )
    return providers


def session_stats(session_id: int) -> dict[str, Any]:
    with sqlite3.connect(DB_PATH) as conn:
        conn.row_factory = sqlite3.Row
        row = conn.execute(
            """
            SELECT
                COUNT(*) AS points,
                AVG(rsrp) AS avg_rsrp,
                MIN(rsrp) AS min_rsrp,
                MAX(rsrp) AS max_rsrp,
                AVG(rsrq) AS avg_rsrq,
                AVG(snr) AS avg_snr,
                AVG(nr_rsrp) AS avg_nr_rsrp,
                COUNT(DISTINCT tech) AS tech_count
            FROM log_rows
            WHERE session_id = ?
            """,
            (session_id,),
        ).fetchone()
    return dict(row) if row else {}
