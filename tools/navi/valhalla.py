"""Client for a local Valhalla instance (see tools/docker-compose.yml).

Two endpoints matter here:

* ``trace_route`` returns a normal route response for the matched track, which
  is where turn maneuvers and street names come from.
* ``trace_attributes`` returns per-edge attribution (surface, road class) plus
  ``matched_points``, which is 1:1 with the input trace and is what lets those
  attributes be mapped back onto the original Komoot points.

Stdlib only -- the whole toolchain needs nothing but pytest to run.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from dataclasses import dataclass

DEFAULT_BASE_URL = "http://localhost:8002"

# Valhalla encodes shapes at precision 6, unlike the precision-5 polylines most
# other services use. Decoding at the wrong precision silently yields
# coordinates off by a factor of ten.
POLYLINE_PRECISION = 6

# `search_radius` is the candidate radius the matcher considers around each
# input point, and it is the single setting that decides whether matching this
# kind of track works at all.
#
# Komoot's planned routes are decimated -- the Venlo fixture has gaps of up to
# 517 m between consecutive points. Below ~75 m the matcher fails to find
# candidate edges across those gaps and the router bridges the hole by picking
# its own cheaper path, silently returning a route 15% SHORTER than the input
# (24.58 km against 28.83 km) with no error raised. At 100 m it follows the
# original faithfully: 29.02 km, and every input point within 22.5 m of the
# result.
#
# 100 is also Valhalla's maximum -- a larger value is rejected with HTTP 400 --
# so there is no headroom above this. `assess_match` exists because of that:
# a sparser route could fail the same way with nowhere left to turn.
TRACE_OPTIONS = {"search_radius": 100, "gps_accuracy": 10}


class ValhallaError(RuntimeError):
    pass


@dataclass(frozen=True)
class Costing:
    """A Valhalla costing model plus its options."""

    name: str
    options: dict

    def request_fields(self) -> dict:
        if not self.options:
            return {"costing": self.name}
        return {"costing": self.name, "costing_options": {self.name: self.options}}


# Komoot sport identifiers appear in <trk><type>. Substring matching keeps this
# robust against the e_/_easy/_advanced variants Komoot layers on.
_PEDESTRIAN_HINTS = ("hike", "jogging", "walk", "mountaineering", "climbing", "nordic", "snowshoe")
_BICYCLE_TYPES = (
    ("mtb", "Mountain"),
    ("downhill", "Mountain"),
    ("gravel", "Hybrid"),
    ("racebike", "Road"),
    ("road_bike", "Road"),
    ("citybike", "City"),
    ("touring", "Hybrid"),
)


def costing_for(activity: str | None) -> Costing:
    """Pick a Valhalla costing model from Komoot's activity type.

    The costing must be permissive enough to traverse whatever Komoot routed
    over, otherwise matching fails on exactly the tracks and paths that make a
    route interesting.
    """
    a = (activity or "").lower()

    if any(h in a for h in _PEDESTRIAN_HINTS):
        return Costing("pedestrian", {"shortest": False})

    bicycle_type = "Hybrid"
    for hint, value in _BICYCLE_TYPES:
        if hint in a:
            bicycle_type = value
            break

    return Costing(
        "bicycle",
        {
            "bicycle_type": bicycle_type,
            # Komoot happily routes over unpaved tracks and paths. Left at the
            # defaults, bicycle costing avoids them strongly enough that map
            # matching drifts onto a parallel road instead.
            "use_roads": 0.5,
            "use_hills": 0.5,
            "avoid_bad_surfaces": 0.0,
        },
    )


# The costing to fall back to when the preferred one matches badly. Pedestrian
# costing traverses almost anything, so it recovers tracks that bicycle costing
# refuses -- at the cost of slightly less apt turn phrasing.
FALLBACK_COSTING = Costing("pedestrian", {"shortest": False})


def decode_polyline(encoded: str, precision: int = POLYLINE_PRECISION) -> list[tuple[float, float]]:
    """Decode an encoded polyline into (lat, lon) pairs."""
    factor = float(10**precision)
    coords: list[tuple[float, float]] = []
    lat = lon = 0
    i = 0
    n = len(encoded)

    while i < n:
        for target in ("lat", "lon"):
            shift = result = 0
            while True:
                if i >= n:
                    raise ValhallaError("truncated polyline")
                b = ord(encoded[i]) - 63
                i += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else (result >> 1)
            if target == "lat":
                lat += delta
            else:
                lon += delta
        coords.append((lat / factor, lon / factor))

    return coords


class Valhalla:
    def __init__(self, base_url: str = DEFAULT_BASE_URL, timeout: float = 180.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _post(self, action: str, payload: dict) -> dict:
        url = f"{self.base_url}/{action}"
        data = json.dumps(payload).encode()
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")
            try:
                detail = json.loads(body).get("error", body)
            except json.JSONDecodeError:
                detail = body
            raise ValhallaError(f"{action} failed ({e.code}): {detail}") from e
        except urllib.error.URLError as e:
            raise ValhallaError(
                f"cannot reach Valhalla at {self.base_url} ({e.reason}). "
                "Start it with: docker compose -f tools/docker-compose.yml up -d"
            ) from e

    def status(self) -> dict:
        url = f"{self.base_url}/status"
        try:
            with urllib.request.urlopen(url, timeout=10) as resp:
                return json.load(resp)
        except urllib.error.URLError as e:
            raise ValhallaError(
                f"cannot reach Valhalla at {self.base_url} ({e.reason}). "
                "Start it with: docker compose -f tools/docker-compose.yml up -d"
            ) from e

    def _shape(self, points) -> list[dict]:
        return [{"lat": round(p.lat, 6), "lon": round(p.lon, 6)} for p in points]

    def trace_route(self, points, costing: Costing) -> dict:
        """Map-match the track and return route directions for it."""
        payload = {
            "shape": self._shape(points),
            # The shape came from Komoot's router, not Valhalla's, so its
            # vertices do not sit on Valhalla's graph nodes and edge_walk
            # cannot apply.
            "shape_match": "map_snap",
            "trace_options": TRACE_OPTIONS,
            **costing.request_fields(),
        }
        return self._post("trace_route", payload)

    def trace_attributes(self, points, costing: Costing) -> dict:
        """Map-match the track and return per-edge attribution."""
        payload = {
            "shape": self._shape(points),
            "shape_match": "map_snap",
            "trace_options": TRACE_OPTIONS,
            "filters": {
                "action": "include",
                "attributes": [
                    "edge.names",
                    "edge.surface",
                    "edge.road_class",
                    "edge.use",
                    "edge.length",
                    "edge.begin_shape_index",
                    "edge.end_shape_index",
                    "matched.point",
                    "matched.type",
                    "matched.edge_index",
                    "matched.distance_from_trace_point",
                    "shape",
                ],
            },
            **costing.request_fields(),
        }
        return self._post("trace_attributes", payload)
