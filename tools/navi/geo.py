"""Geodesic helpers shared by the preprocessing pipeline.

Distances are metres throughout. The equirectangular helpers mirror the Kotlin
implementation in the Android app (`geo/Geo.kt`) so that snapping behaves
identically on both sides.
"""

from __future__ import annotations

import math

EARTH_RADIUS_M = 6_371_000.0

# Metres per degree, used by the local-plane projection. Good to well under a
# metre at the scale of a single track segment, which is all we use it for.
M_PER_DEG_LAT = 110_540.0
M_PER_DEG_LON_EQUATOR = 111_320.0


def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in metres."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def bearing(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Initial bearing from point 1 to point 2, in degrees clockwise from north."""
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dl = math.radians(lon2 - lon1)
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return math.degrees(math.atan2(y, x)) % 360.0


def bearing_delta(b1: float, b2: float) -> float:
    """Smallest absolute angle between two bearings, in degrees (0..180)."""
    d = abs(b1 - b2) % 360.0
    return 360.0 - d if d > 180.0 else d


def cumulative_distances(points) -> list[float]:
    """Running distance along a track, starting at 0. Length matches `points`."""
    cum = [0.0]
    total = 0.0
    for a, b in zip(points, points[1:]):
        total += haversine(a.lat, a.lon, b.lat, b.lon)
        cum.append(total)
    return cum


class LocalPlane:
    """Equirectangular projection around a reference latitude.

    Converts lat/lon to local metre coordinates so segment maths is plain
    Euclidean geometry. Accurate enough for the few-hundred-metre spans we
    project onto, and far cheaper than a haversine per segment.
    """

    __slots__ = ("lat0", "lon0", "m_per_deg_lon")

    def __init__(self, lat0: float, lon0: float) -> None:
        self.lat0 = lat0
        self.lon0 = lon0
        self.m_per_deg_lon = M_PER_DEG_LON_EQUATOR * math.cos(math.radians(lat0))

    def xy(self, lat: float, lon: float) -> tuple[float, float]:
        return ((lon - self.lon0) * self.m_per_deg_lon, (lat - self.lat0) * M_PER_DEG_LAT)


def point_to_segment(
    px: float, py: float, ax: float, ay: float, bx: float, by: float
) -> tuple[float, float, float]:
    """Project (px,py) onto segment a->b in a plane.

    Returns (t, distance, _) where t in [0,1] is the position along the segment
    and distance is the perpendicular distance to the projected point.
    Degenerate (zero-length) segments return t=0 and the distance to `a`.
    """
    dx, dy = bx - ax, by - ay
    seg_sq = dx * dx + dy * dy
    if seg_sq <= 1e-12:
        return 0.0, math.hypot(px - ax, py - ay), 0.0
    t = ((px - ax) * dx + (py - ay) * dy) / seg_sq
    t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
    cx, cy = ax + t * dx, ay + t * dy
    return t, math.hypot(px - cx, py - cy), seg_sq


def nearest_point_index(points, lat: float, lon: float, start: int = 0, end: int | None = None) -> tuple[int, float]:
    """Index of the track point nearest to (lat, lon), searched over [start, end).

    Restricting the range is what keeps a route that doubles back on itself from
    binding a later feature to an earlier, coincident part of the track.
    """
    if end is None:
        end = len(points)
    start = max(0, start)
    end = min(len(points), end)
    if start >= end:
        raise ValueError(f"empty search range [{start}, {end})")

    plane = LocalPlane(lat, lon)
    px, py = plane.xy(lat, lon)
    best_i, best_d2 = start, float("inf")
    for i in range(start, end):
        p = points[i]
        x, y = plane.xy(p.lat, p.lon)
        d2 = (x - px) ** 2 + (y - py) ** 2
        if d2 < best_d2:
            best_i, best_d2 = i, d2
    return best_i, math.sqrt(best_d2)
