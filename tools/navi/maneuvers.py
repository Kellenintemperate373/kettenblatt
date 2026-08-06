"""Turn Valhalla's map-matching output into annotations on the original track.

Everything here maps *back onto the Komoot points*. The original geometry stays
authoritative for navigation, so a poor match costs street names rather than
corrupting the line being followed.

The mapping is not a plain nearest-neighbour lookup. The Venlo fixture is a
loop that revisits 47 coordinates, so a maneuver on the return leg sits right
on top of the outbound leg. Nearest-neighbour would bind it to the wrong one.
Both `map_maneuvers` and the surface spans therefore advance a cursor and never
look backwards.
"""

from __future__ import annotations

import bisect
from dataclasses import dataclass

from . import geo

# Valhalla maneuver type enum, confirmed against a live 3.5.1 response rather
# than taken from memory. Only genuine decision points are kept.
START_TYPES = {1, 2, 3}
DESTINATION_TYPES = {4, 5, 6}

MANEUVER_NAMES = {
    9: "slight_right",
    10: "turn_right",
    11: "sharp_right",
    12: "uturn_right",
    13: "uturn_left",
    14: "sharp_left",
    15: "turn_left",
    16: "slight_left",
    17: "ramp_straight",
    18: "ramp_right",
    19: "ramp_left",
    20: "exit_right",
    21: "exit_left",
    22: "keep_straight",
    23: "keep_right",
    24: "keep_left",
    25: "merge",
    26: "roundabout",
    28: "ferry",
    38: "merge_right",
    39: "merge_left",
    41: "steps",
}

# Deliberately excluded, with reasons:
#   7  becomes    - road changes name, no action required
#   8  continue   - explicitly "carry on", the opposite of a decision point
#   27 roundabout exit - the enter maneuver already says which exit to take
#   29 ferry exit - informational; the enter maneuver is the actionable one
IGNORED_TYPES = {0, 7, 8, 27, 29} | START_TYPES | DESTINATION_TYPES

# How far the search for a maneuver's track index may roam from where the
# route's own distance measurement puts it. Generous enough to absorb the
# difference between matched and original length, tight enough that it cannot
# reach a coincident point on another leg of a loop.
MANEUVER_SEARCH_TOLERANCE_M = 400.0

# A trace_route whose length differs from the original by more than this is not
# following the same path, and its maneuvers describe a route the rider will
# not take. See TRACE_OPTIONS in valhalla.py for how this failure looks.
MAX_LENGTH_DEVIATION = 0.05


@dataclass(frozen=True)
class Maneuver:
    idx: int
    type: str
    street: str | None
    instruction: str

    def as_dict(self) -> dict:
        d = {"idx": self.idx, "type": self.type, "instruction": self.instruction}
        if self.street:
            d["street"] = self.street
        return d


@dataclass(frozen=True)
class Span:
    """A run of consecutive track points sharing the same road attribution."""

    start: int
    end: int
    surface: str | None = None
    road_class: str | None = None
    use: str | None = None

    def as_dict(self) -> dict:
        d: dict = {"from": self.start, "to": self.end}
        if self.surface:
            d["surface"] = self.surface
        if self.road_class:
            d["roadClass"] = self.road_class
        if self.use:
            d["use"] = self.use
        return d


@dataclass
class MatchQuality:
    costing: str
    total_points: int
    matched: int
    interpolated: int
    unmatched: int
    mean_offset_m: float
    max_offset_m: float
    original_length_m: float
    matched_length_m: float

    @property
    def length_deviation(self) -> float:
        if self.original_length_m <= 0:
            return 0.0
        return (self.matched_length_m - self.original_length_m) / self.original_length_m

    @property
    def is_usable(self) -> bool:
        return abs(self.length_deviation) <= MAX_LENGTH_DEVIATION

    def summary(self) -> str:
        return (
            f"matched {self.matched}/{self.total_points} "
            f"({self.interpolated} interpolated, {self.unmatched} unmatched) "
            f"via {self.costing}; offset mean {self.mean_offset_m:.1f} m "
            f"max {self.max_offset_m:.1f} m; length {self.matched_length_m / 1000:.2f} km "
            f"vs {self.original_length_m / 1000:.2f} km ({self.length_deviation:+.1%})"
        )

    def as_dict(self) -> dict:
        return {
            "costing": self.costing,
            "total": self.total_points,
            "matched": self.matched,
            "interpolated": self.interpolated,
            "unmatched": self.unmatched,
            "meanOffsetM": round(self.mean_offset_m, 2),
            "maxOffsetM": round(self.max_offset_m, 2),
            "lengthDeviation": round(self.length_deviation, 4),
        }


def _leg_maneuvers(trace_route: dict):
    """Flatten legs into (maneuver, decoded leg shape) pairs."""
    from .valhalla import decode_polyline

    for leg in trace_route.get("trip", {}).get("legs", []):
        shape = decode_polyline(leg["shape"])
        for m in leg.get("maneuvers", []):
            yield m, shape


def route_length_m(trace_route: dict) -> float:
    """Total matched route length in metres.

    Valhalla reports kilometres or miles depending on the request; this asks
    the response which it used rather than assuming.
    """
    trip = trace_route.get("trip", {})
    length = trip.get("summary", {}).get("length", 0.0)
    units = trip.get("units", "kilometers")
    return length * (1609.344 if units.startswith("mi") else 1000.0)


def map_maneuvers(trace_route: dict, points, cum_dist: list[float]) -> list[Maneuver]:
    """Bind Valhalla maneuvers to indices in the original track.

    Valhalla's `begin_shape_index` addresses its own matched geometry, which is
    denser than the input (823 points against 606 on the Venlo fixture), so the
    index cannot be used directly. Each maneuver is instead located
    geographically, restricted to a window around where the route's own running
    distance says it should be and never allowed to move backwards.
    """
    out: list[Maneuver] = []
    cursor = 0
    travelled_m = 0.0

    for m, shape in _leg_maneuvers(trace_route):
        mtype = m.get("type", 0)
        # Length is consumed for every maneuver, including skipped ones, so the
        # running distance stays aligned with the route.
        begin = m.get("begin_shape_index", 0)
        seg_len_m = m.get("length", 0.0) * 1000.0

        if mtype in IGNORED_TYPES:
            travelled_m += seg_len_m
            continue

        name = MANEUVER_NAMES.get(mtype)
        if name is None:
            travelled_m += seg_len_m
            continue

        if not (0 <= begin < len(shape)):
            travelled_m += seg_len_m
            continue
        lat, lon = shape[begin]

        lo = bisect.bisect_left(cum_dist, travelled_m - MANEUVER_SEARCH_TOLERANCE_M)
        hi = bisect.bisect_right(cum_dist, travelled_m + MANEUVER_SEARCH_TOLERANCE_M)
        lo = max(lo, cursor)
        hi = max(hi, lo + 1)

        idx, _ = geo.nearest_point_index(points, lat, lon, start=lo, end=hi)
        cursor = idx

        streets = m.get("street_names") or m.get("begin_street_names") or []
        out.append(
            Maneuver(
                idx=idx,
                type=name,
                street=streets[0] if streets else None,
                instruction=(m.get("instruction") or "").strip(),
            )
        )
        travelled_m += seg_len_m

    return out


def attribute_spans(trace_attributes: dict, n_points: int) -> list[Span]:
    """Group the track into runs of constant road attribution.

    `matched_points` is 1:1 with the input, so this mapping needs no geometric
    search at all -- each original point already carries its edge index.
    """
    edges = trace_attributes.get("edges", [])
    matched = trace_attributes.get("matched_points", [])

    def attrs_at(i: int):
        if i >= len(matched):
            return (None, None, None)
        ei = matched[i].get("edge_index")
        if ei is None or ei >= len(edges):
            return (None, None, None)
        e = edges[ei]
        return (e.get("surface"), e.get("road_class"), e.get("use"))

    spans: list[Span] = []
    start = 0
    current = attrs_at(0)
    for i in range(1, n_points):
        a = attrs_at(i)
        if a != current:
            if any(current):
                spans.append(Span(start, i - 1, *current))
            start, current = i, a
    if any(current):
        spans.append(Span(start, n_points - 1, *current))

    return spans


def assess_match(
    trace_attributes: dict,
    trace_route: dict,
    costing_name: str,
    original_length_m: float,
    n_points: int,
) -> MatchQuality:
    matched_points = trace_attributes.get("matched_points", [])
    counts = {"matched": 0, "interpolated": 0, "unmatched": 0}
    offsets: list[float] = []
    for p in matched_points:
        counts[p.get("type", "unmatched")] = counts.get(p.get("type", "unmatched"), 0) + 1
        if p.get("type") != "unmatched":
            offsets.append(p.get("distance_from_trace_point", 0.0) or 0.0)

    return MatchQuality(
        costing=costing_name,
        total_points=n_points,
        matched=counts.get("matched", 0),
        interpolated=counts.get("interpolated", 0),
        unmatched=counts.get("unmatched", 0),
        mean_offset_m=(sum(offsets) / len(offsets)) if offsets else 0.0,
        max_offset_m=max(offsets) if offsets else 0.0,
        original_length_m=original_length_m,
        matched_length_m=route_length_m(trace_route),
    )
