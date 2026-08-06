"""Komoot GPX parsing.

Tags are matched on their *local* name, so GPX 1.0, GPX 1.1 and any namespace
prefix all parse without special-casing. Komoot emits GPX 1.1 with the default
namespace, but files that have been round-tripped through other tools often
don't.
"""

from __future__ import annotations

import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

from .geo import haversine

# Consecutive points closer than this collapse to one. Zero-length segments
# make bearings undefined and add nothing to the geometry.
MIN_SEPARATION_M = 0.1


@dataclass(frozen=True)
class TrackPoint:
    lat: float
    lon: float
    ele: float | None = None
    time: str | None = None


@dataclass(frozen=True)
class Waypoint:
    lat: float
    lon: float
    name: str | None = None
    sym: str | None = None
    desc: str | None = None


@dataclass
class Gpx:
    name: str
    activity: str | None
    points: list[TrackPoint]
    waypoints: list[Waypoint]
    dropped_duplicates: int = 0


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _children(el, name: str):
    if el is None:
        return []
    return [c for c in el if _local(c.tag) == name]


def _child(el, name: str):
    for c in _children(el, name):
        return c
    return None


def _text(el, name: str) -> str | None:
    c = _child(el, name)
    if c is None or c.text is None:
        return None
    t = c.text.strip()
    return t or None


def _read_point(el) -> TrackPoint | None:
    lat, lon = el.get("lat"), el.get("lon")
    if lat is None or lon is None:
        return None
    ele = _text(el, "ele")
    return TrackPoint(
        lat=float(lat),
        lon=float(lon),
        ele=float(ele) if ele is not None else None,
        time=_text(el, "time"),
    )


def _dedupe(points: list[TrackPoint]) -> tuple[list[TrackPoint], int]:
    """Drop consecutive near-identical points, keeping the first of each run.

    A loop whose last point equals its first is left intact -- that closure is
    meaningful, and the two are not consecutive.
    """
    if not points:
        return [], 0
    out = [points[0]]
    for p in points[1:]:
        prev = out[-1]
        if haversine(prev.lat, prev.lon, p.lat, p.lon) < MIN_SEPARATION_M:
            continue
        out.append(p)
    return out, len(points) - len(out)


def parse(path: str | Path) -> Gpx:
    """Parse a GPX file into track points, waypoints and metadata.

    Prefers `<trk>` (what Komoot exports); falls back to `<rte>` for files from
    tools that only emit routes.
    """
    path = Path(path)
    root = ET.parse(path).getroot()

    points: list[TrackPoint] = []
    activity: str | None = None
    track_name: str | None = None

    trk = _child(root, "trk")
    if trk is not None:
        track_name = _text(trk, "name")
        activity = _text(trk, "type")
        # Multiple segments are concatenated: planned Komoot routes have one,
        # but recorded activities split across pauses.
        for seg in _children(trk, "trkseg"):
            for el in _children(seg, "trkpt"):
                p = _read_point(el)
                if p is not None:
                    points.append(p)

    if not points:
        rte = _child(root, "rte")
        if rte is not None:
            track_name = track_name or _text(rte, "name")
            for el in _children(rte, "rtept"):
                p = _read_point(el)
                if p is not None:
                    points.append(p)

    points, dropped = _dedupe(points)
    if len(points) < 2:
        raise ValueError(f"{path.name}: need at least 2 track points, found {len(points)}")

    waypoints = []
    for el in _children(root, "wpt"):
        lat, lon = el.get("lat"), el.get("lon")
        if lat is None or lon is None:
            continue
        waypoints.append(
            Waypoint(
                lat=float(lat),
                lon=float(lon),
                name=_text(el, "name"),
                sym=_text(el, "sym"),
                desc=_text(el, "desc"),
            )
        )

    metadata_name = _text(_child(root, "metadata"), "name")
    name = metadata_name or track_name or path.stem

    return Gpx(
        name=name,
        activity=activity,
        points=points,
        waypoints=waypoints,
        dropped_duplicates=dropped,
    )
