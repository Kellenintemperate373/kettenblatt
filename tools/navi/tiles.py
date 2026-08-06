"""Build an offline MBTiles pack covering the corridor around a route.

Which shape is cheaper depends on the route, so both are computed and the
smaller wins:

* A **corridor** of tiles within `buffer_m` of the track is the big saving on
  long linear routes -- a 127 km point-to-point needs a tenth of the tiles its
  bounding box would (4216 against 42680 at zoom 12-16).
* A **bounding box** wins on compact loops. The Venlo route folds back through
  a 7 x 5 km area, so a 500 m corridor around 29 km of track actually costs
  *more* than the box that contains it (503 tiles against 431) -- and the box
  additionally fills in the loop's interior.

Two things about tile sources are not optional:

* **Standard OSM tiles are not usable here.** The OSMF tile policy forbids bulk
  downloading, which is also why osmdroid refuses it on-device (its Mapnik
  source carries FLAG_NO_BULK). The sources below either permit caching
  outright or are used within their stated limits.
* **A real User-Agent is required.** Community tile servers block the default
  Python one.
"""

from __future__ import annotations

import http.client
import math
import sqlite3
import sys
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

USER_AGENT = "Kettenblatt/1.0 (personal route preparation; +https://github.com/nils-fl/kettenblatt)"

# Equatorial circumference, for converting a metre buffer into whole tiles.
EARTH_CIRCUMFERENCE_M = 40_075_016.686

# Half-width of the corridor. The off-route alert fires at 40 m, so 500 m is
# already a wide margin for recovering a lost line, and it costs a third fewer
# tiles than a kilometre on long routes.
DEFAULT_BUFFER_M = 500.0
DEFAULT_SOURCE = "opentopomap"

# Downloading faster than this is antisocial on volunteer-run servers.
MAX_WORKERS = 2
RETRY_DELAYS = (1.0, 3.0, 8.0)


@dataclass(frozen=True)
class TileSource:
    name: str
    url: str
    max_zoom: int
    attribution: str
    needs_key: bool = False
    subdomains: tuple[str, ...] = ()

    def tile_url(self, z: int, x: int, y: int, api_key: str | None) -> str:
        url = self.url.format(
            z=z, x=x, y=y, s=self.subdomains[(x + y) % len(self.subdomains)] if self.subdomains else "",
            key=api_key or "",
        )
        return url


SOURCES: dict[str, TileSource] = {
    # Topographic styling, contours and paths -- well suited to hiking. Works
    # with no signup. Their tile server is volunteer-run, so keep downloads to
    # actual route corridors.
    "opentopomap": TileSource(
        name="OpenTopoMap",
        url="https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
        max_zoom=17,
        attribution="Map data (c) OpenStreetMap contributors, SRTM | Style (c) OpenTopoMap (CC-BY-SA)",
        subdomains=("a", "b", "c"),
    ),
    # Purpose-built cycling and outdoor styles. The free tier explicitly permits
    # caching, which makes this the right choice if you prepare routes often.
    "thunderforest-outdoors": TileSource(
        name="Thunderforest Outdoors",
        url="https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}",
        max_zoom=22,
        attribution="Maps (c) Thunderforest, Data (c) OpenStreetMap contributors",
        needs_key=True,
    ),
    "thunderforest-cycle": TileSource(
        name="Thunderforest OpenCycleMap",
        url="https://tile.thunderforest.com/cycle/{z}/{x}/{y}.png?apikey={key}",
        max_zoom=22,
        attribution="Maps (c) Thunderforest, Data (c) OpenStreetMap contributors",
        needs_key=True,
    ),
}


def deg2tile(lat: float, lon: float, zoom: int) -> tuple[int, int]:
    """Slippy-map (XYZ) tile containing a coordinate."""
    n = 2**zoom
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(max(min(lat, 85.05112878), -85.05112878))
    y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def tile_width_m(lat: float, zoom: int) -> float:
    return EARTH_CIRCUMFERENCE_M * math.cos(math.radians(lat)) / (2**zoom)


def corridor_tiles(points, zoom: int, buffer_m: float) -> set[tuple[int, int]]:
    """Tiles within `buffer_m` of the track at a given zoom."""
    if not points:
        return set()

    mean_lat = sum(p[0] for p in points) / len(points)
    pad = max(0, math.ceil(buffer_m / tile_width_m(mean_lat, zoom)))

    out: set[tuple[int, int]] = set()
    n = 2**zoom
    for lat, lon, *_ in points:
        cx, cy = deg2tile(lat, lon, zoom)
        for dx in range(-pad, pad + 1):
            for dy in range(-pad, pad + 1):
                x, y = cx + dx, cy + dy
                if 0 <= x < n and 0 <= y < n:
                    out.add((x, y))
    return out


def bbox_tiles(points, zoom: int) -> set[tuple[int, int]]:
    """Every tile in the track's bounding box at a given zoom."""
    if not points:
        return set()

    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    x0, y0 = deg2tile(max(lats), min(lons), zoom)
    x1, y1 = deg2tile(min(lats), max(lons), zoom)
    return {(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)}


def tiles_for(points, zoom: int, buffer_m: float) -> tuple[set[tuple[int, int]], str]:
    """The cheaper of the corridor and the bounding box, with which was chosen.

    Falling back to the box when it is smaller is not just a saving: on a route
    that folds back on itself the box also fills in the interior, so the rider
    gets map for the ground between the legs as well.
    """
    corridor = corridor_tiles(points, zoom, buffer_m)
    box = bbox_tiles(points, zoom)
    return (corridor, "corridor") if len(corridor) <= len(box) else (box, "bbox")


_connections = threading.local()


def _connection(host: str) -> http.client.HTTPSConnection:
    """One kept-alive connection per worker thread, per host.

    A fresh TLS handshake for each of several hundred tiles dominates the wall
    clock -- more than the tile transfers themselves at these sizes.
    """
    cache = getattr(_connections, "by_host", None)
    if cache is None:
        cache = _connections.by_host = {}
    conn = cache.get(host)
    if conn is None:
        conn = cache[host] = http.client.HTTPSConnection(host, timeout=30)
    return conn


def _drop_connection(host: str) -> None:
    cache = getattr(_connections, "by_host", None)
    if cache and host in cache:
        try:
            cache.pop(host).close()
        except Exception:  # noqa: BLE001 - a dead connection is why we are here
            pass


def _request(url: str) -> tuple[int, bytes]:
    """One GET over the thread's kept-alive connection."""
    parts = urllib.parse.urlsplit(url)
    path = parts.path + (f"?{parts.query}" if parts.query else "")
    try:
        conn = _connection(parts.netloc)
        conn.request("GET", path, headers={"User-Agent": USER_AGENT, "Connection": "keep-alive"})
        resp = conn.getresponse()
        return resp.status, resp.read()
    except (http.client.HTTPException, OSError):
        # The server closed an idle connection; a single reconnect settles it.
        _drop_connection(parts.netloc)
        conn = _connection(parts.netloc)
        conn.request("GET", path, headers={"User-Agent": USER_AGENT, "Connection": "keep-alive"})
        resp = conn.getresponse()
        return resp.status, resp.read()


def _fetch(url: str) -> bytes | None:
    """Download one tile. Returns None for a tile the server does not have."""
    for attempt, delay in enumerate((0.0, *RETRY_DELAYS)):
        if delay:
            time.sleep(delay)
        try:
            status, body = _request(url)
        except (http.client.HTTPException, OSError):
            if attempt == len(RETRY_DELAYS):
                raise
            continue

        if status == 200:
            return body
        if status in (404, 204):
            return None
        # 429/5xx are worth retrying; anything else is not.
        if status not in (429, 500, 502, 503, 504) or attempt == len(RETRY_DELAYS):
            raise RuntimeError(f"tile fetch failed ({status}) for {url}")
    return None


def _init_mbtiles(conn: sqlite3.Connection, name: str, source: TileSource,
                  bbox: list[float], zoom_min: int, zoom_max: int) -> None:
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS metadata (name text, value text);
        CREATE TABLE IF NOT EXISTS tiles (
            zoom_level integer, tile_column integer, tile_row integer, tile_data blob
        );
        CREATE UNIQUE INDEX IF NOT EXISTS tile_index
            ON tiles (zoom_level, tile_column, tile_row);
        """
    )
    min_lat, min_lon, max_lat, max_lon = bbox
    conn.executemany(
        "INSERT INTO metadata (name, value) VALUES (?, ?)",
        [
            ("name", name),
            ("format", "png"),
            ("type", "baselayer"),
            ("version", "1.0"),
            ("description", f"Offline corridor for {name}"),
            ("attribution", source.attribution),
            ("bounds", f"{min_lon},{min_lat},{max_lon},{max_lat}"),
            ("minzoom", str(zoom_min)),
            ("maxzoom", str(zoom_max)),
        ],
    )
    conn.commit()


def _existing_tiles(out_path: Path, src: TileSource) -> set[tuple[int, int, int]] | None:
    """Tiles already in an existing pack, or None if it cannot be reused.

    Returns slippy-map (z, x, y) triples, undoing the stored TMS row order so the
    caller can compare against what it wants to download.
    """
    if not out_path.exists():
        return None

    try:
        conn = sqlite3.connect(out_path)
        try:
            meta = dict(conn.execute("SELECT name, value FROM metadata").fetchall())
            if meta.get("attribution") != src.attribution:
                return None
            return {
                (z, x, (2**z - 1) - row)
                for z, x, row in conn.execute(
                    "SELECT zoom_level, tile_column, tile_row FROM tiles"
                )
            }
        finally:
            conn.close()
    except sqlite3.Error:
        # Half-written or not an MBTiles file at all; start over.
        return None


def build(
    bundle,
    out_path: str | Path,
    source: str = DEFAULT_SOURCE,
    zoom_min: int = 12,
    zoom_max: int = 16,
    buffer_m: float = DEFAULT_BUFFER_M,
    api_key: str | None = None,
    confirm: bool = True,
    log=lambda _msg: None,
) -> Path | None:
    """Download the route corridor into an MBTiles file."""
    src = SOURCES.get(source)
    if src is None:
        raise ValueError(f"unknown tile source {source!r}; choose from {sorted(SOURCES)}")
    if src.needs_key and not api_key:
        raise ValueError(f"{src.name} needs an API key -- pass --tile-api-key")
    if zoom_max > src.max_zoom:
        log(f"  {src.name} tops out at zoom {src.max_zoom}; clamping")
        zoom_max = src.max_zoom
    if zoom_min > zoom_max:
        raise ValueError(f"empty zoom range {zoom_min}-{zoom_max}")

    wanted: list[tuple[int, int, int]] = []
    shapes: set[str] = set()
    for z in range(zoom_min, zoom_max + 1):
        chosen, shape = tiles_for(bundle.points, z, buffer_m)
        shapes.add(shape)
        for x, y in sorted(chosen):
            wanted.append((z, x, y))

    # Measured against OpenTopoMap over this route: 27-48 KB per tile.
    estimate_mb = len(wanted) * 35 / 1024
    log(
        f"  {len(wanted)} tiles, zoom {zoom_min}-{zoom_max}, "
        f"{'/'.join(sorted(shapes))} shape ({buffer_m:.0f} m buffer), "
        f"~{estimate_mb:.0f} MB from {src.name}"
    )

    if confirm:
        if not sys.stdin.isatty():
            raise RuntimeError("tile download needs confirmation; pass -y to proceed")
        if input("  download? [y/N] ").strip().lower() not in ("y", "yes"):
            log("  skipped")
            return None

    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    # Downloads take minutes and get interrupted. Keep what is already there, as
    # long as it came from the same source -- mixing styles in one pack would
    # give a map that changes appearance as you ride across it.
    already = _existing_tiles(out_path, src)
    if already is None:
        if out_path.exists():
            out_path.unlink()
    elif already:
        wanted = [t for t in wanted if t not in already]
        log(f"  resuming: {len(already)} tiles already present, {len(wanted)} to fetch")
        if not wanted:
            log("  nothing left to download")
            return out_path

    conn = sqlite3.connect(out_path)
    try:
        if already is None:
            _init_mbtiles(conn, bundle.name, src, bundle.bbox(), zoom_min, zoom_max)

        done = skipped = 0
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as pool:
            for (z, x, y), data in zip(
                wanted,
                pool.map(lambda t: _fetch(src.tile_url(t[0], t[1], t[2], api_key)), wanted),
            ):
                if data is None:
                    skipped += 1
                    continue
                # MBTiles addresses rows in TMS order, with the origin at the
                # bottom; slippy-map y counts from the top. Omitting this flip
                # produces a file full of tiles that render as a blank map.
                tms_y = (2**z - 1) - y
                conn.execute(
                    "INSERT OR REPLACE INTO tiles VALUES (?, ?, ?, ?)",
                    (z, x, tms_y, sqlite3.Binary(data)),
                )
                done += 1
                if done % 100 == 0:
                    conn.commit()
                    log(f"    {done}/{len(wanted)}")
        conn.commit()
        if skipped:
            log(f"  {skipped} tiles unavailable from the server")
        log(f"  stored {done} tiles")
    finally:
        conn.close()

    return out_path
