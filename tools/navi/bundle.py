"""The `.navi.json` navigation bundle: what the Android app actually consumes.

Everything the app needs is precomputed here so that opening a route on the
phone is a parse and nothing more. Indices in `maneuvers` and `surfaces` refer
to positions in `points`.

Coordinates are rounded to 6 decimal places (~0.1 m) and distances to 0.1 m.
Full float repr would roughly double the file for precision no GPS can use.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

# v2 adds `maneuversReverse`. The app reads v1 bundles unchanged -- they simply
# have no cues for the backward direction.
BUNDLE_VERSION = 2
BUNDLE_SUFFIX = ".navi.json"

COORD_DP = 6
ELEVATION_DP = 1
DISTANCE_DP = 1


@dataclass
class Bundle:
    name: str
    activity: str | None
    points: list[tuple[float, float, float]]
    cum_dist_m: list[float]
    cum_ascent_m: list[float]
    maneuvers: list = field(default_factory=list)
    # Cues for riding backwards; indices are into the reversed point order.
    reverse_maneuvers: list = field(default_factory=list)
    surfaces: list = field(default_factory=list)
    waypoints: list = field(default_factory=list)
    match_quality: dict | None = None
    warnings: list[str] = field(default_factory=list)

    @property
    def distance_m(self) -> float:
        return self.cum_dist_m[-1] if self.cum_dist_m else 0.0

    @property
    def ascent_m(self) -> float:
        return self.cum_ascent_m[-1] if self.cum_ascent_m else 0.0

    def bbox(self) -> list[float]:
        lats = [p[0] for p in self.points]
        lons = [p[1] for p in self.points]
        return [
            round(min(lats), COORD_DP),
            round(min(lons), COORD_DP),
            round(max(lats), COORD_DP),
            round(max(lons), COORD_DP),
        ]

    def to_dict(self) -> dict:
        d: dict = {
            "version": BUNDLE_VERSION,
            "name": self.name,
            "activity": self.activity,
            "distanceM": round(self.distance_m, DISTANCE_DP),
            "ascentM": round(self.ascent_m, DISTANCE_DP),
            "bbox": self.bbox(),
            "points": [
                [round(lat, COORD_DP), round(lon, COORD_DP), round(ele, ELEVATION_DP)]
                for lat, lon, ele in self.points
            ],
            "cumDistM": [round(v, DISTANCE_DP) for v in self.cum_dist_m],
            "cumAscentM": [round(v, DISTANCE_DP) for v in self.cum_ascent_m],
            "maneuvers": [m.as_dict() for m in self.maneuvers],
            "maneuversReverse": [m.as_dict() for m in self.reverse_maneuvers],
            "surfaces": [s.as_dict() for s in self.surfaces],
            "waypoints": [
                {
                    "lat": round(w.lat, COORD_DP),
                    "lon": round(w.lon, COORD_DP),
                    **({"name": w.name} if w.name else {}),
                    **({"sym": w.sym} if w.sym else {}),
                    **({"desc": w.desc} if w.desc else {}),
                }
                for w in self.waypoints
            ],
        }
        if self.match_quality is not None:
            d["matchQuality"] = self.match_quality
        if self.warnings:
            d["warnings"] = self.warnings
        return d

    def write(self, path: str | Path) -> Path:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        # Separators without spaces; this file is transferred to a phone, not read.
        path.write_text(json.dumps(self.to_dict(), separators=(",", ":"), ensure_ascii=False))
        return path


def _stem(gpx_path: Path) -> str:
    return gpx_path.name[: -len(gpx_path.suffix)] if gpx_path.suffix else gpx_path.name


def output_path(gpx_path: str | Path, out_dir: str | Path | None = None) -> Path:
    gpx_path = Path(gpx_path)
    directory = Path(out_dir) if out_dir else gpx_path.parent
    return directory / f"{_stem(gpx_path)}{BUNDLE_SUFFIX}"


def tiles_path(gpx_path: str | Path, out_dir: str | Path | None = None) -> Path:
    """Companion .mbtiles path for a route, alongside its bundle."""
    gpx_path = Path(gpx_path)
    directory = Path(out_dir) if out_dir else gpx_path.parent
    return directory / f"{_stem(gpx_path)}.mbtiles"
