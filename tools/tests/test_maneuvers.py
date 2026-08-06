"""Maneuver mapping and match assessment.

These run against frozen Valhalla responses so they are fast and deterministic.
`test_valhalla_live.py` covers the parts that need a running server.
"""

from __future__ import annotations

import gzip
import json
from pathlib import Path

import pytest

from navi import geo, gpx, maneuvers, valhalla

FIXTURES = Path(__file__).parent / "fixtures"


def _load(name: str) -> dict:
    with gzip.open(FIXTURES / f"venlo_{name}.json.gz", "rt") as f:
        return json.load(f)


@pytest.fixture(scope="module")
def venlo():
    return gpx.parse(FIXTURES / "venlo.gpx")


@pytest.fixture(scope="module")
def cum(venlo):
    return geo.cumulative_distances(venlo.points)


@pytest.fixture(scope="module")
def trace_route():
    return _load("trace_route")


@pytest.fixture(scope="module")
def trace_attributes():
    return _load("trace_attributes")


@pytest.fixture(scope="module")
def mapped(trace_route, venlo, cum):
    return maneuvers.map_maneuvers(trace_route, venlo.points, cum)


# --- the core claim -------------------------------------------------------


def test_filters_out_the_geometric_noise(mapped, venlo, cum):
    """Far fewer cues than bearing-change detection would produce.

    606 points yield 119 vertices turning more than 25 degrees -- an alert
    every 240 m, which is the whole reason for map matching. Junction-aware
    maneuvers must land well below that.
    """
    naive = sum(
        1
        for i in range(1, len(venlo.points) - 1)
        if geo.bearing_delta(
            geo.bearing(venlo.points[i - 1].lat, venlo.points[i - 1].lon,
                        venlo.points[i].lat, venlo.points[i].lon),
            geo.bearing(venlo.points[i].lat, venlo.points[i].lon,
                        venlo.points[i + 1].lat, venlo.points[i + 1].lon),
        ) > 25.0
    )
    assert naive == 119
    assert len(mapped) < naive * 0.7
    # Sanity in the other direction: a 29 km route through Dutch field roads
    # genuinely does turn often, so an empty or near-empty list means the
    # filter has gone wrong.
    assert len(mapped) > 30


def test_every_maneuver_is_a_real_decision_point(mapped):
    assert all(m.type in maneuvers.MANEUVER_NAMES.values() for m in mapped)
    # "Continue" and "becomes" carry no action and must never survive.
    assert not any(m.type in {"continue", "becomes"} for m in mapped)


def test_maneuvers_are_named(mapped):
    """Most turns carry a street name; the rest are unnamed Dutch cycleways.

    81% named on this route. The remainder are `highway=cycleway` ways with no
    name in OSM, which is normal in the Netherlands -- Valhalla still phrases
    those usefully ("Turn right onto the cycleway"), so the banner never ends
    up blank.
    """
    named = [m for m in mapped if m.street]
    assert len(named) / len(mapped) > 0.75
    assert any(m.street == "Herungerberg" for m in mapped)
    assert all(m.instruction for m in mapped)


# --- the loop-overlap problem --------------------------------------------


def test_indices_never_move_backwards(mapped):
    """The property that stops a return-leg turn binding to the outbound leg."""
    idxs = [m.idx for m in mapped]
    assert idxs == sorted(idxs)


def test_maneuvers_stay_within_the_track(mapped, venlo):
    assert all(0 <= m.idx < len(venlo.points) for m in mapped)


def test_late_maneuvers_map_to_late_indices(mapped, venlo, cum):
    """The overlapping start/end of this loop is where nearest-neighbour fails.

    The final points retrace the first ones exactly, so a plain nearest-point
    lookup would bind closing maneuvers back to index 0.
    """
    last = mapped[-1]
    assert last.idx > len(venlo.points) * 0.8
    assert cum[last.idx] > cum[-1] * 0.8


def test_maneuver_positions_track_route_distance(mapped, trace_route, cum):
    """Each maneuver should sit near where the route's own distance puts it."""
    assert cum[mapped[0].idx] < 500.0
    # Monotonic and spread across the whole route rather than bunched.
    positions = [cum[m.idx] for m in mapped]
    assert positions[-1] - positions[0] > cum[-1] * 0.9


# --- match assessment -----------------------------------------------------


def test_assess_match_accepts_a_good_match(trace_attributes, trace_route, venlo, cum):
    q = maneuvers.assess_match(
        trace_attributes, trace_route, "bicycle", cum[-1], len(venlo.points)
    )
    assert q.unmatched == 0
    assert q.matched > 500
    assert abs(q.length_deviation) < 0.02
    assert q.is_usable


def test_assess_match_rejects_a_shortcutting_route(trace_attributes, venlo, cum):
    """The failure mode that a too-small search_radius produces.

    Valhalla returns a perfectly well-formed response describing a route 15%
    shorter than the input, with no error. Only the length check catches it.
    """
    shortcut = {"trip": {"units": "kilometers", "summary": {"length": 24.581}, "legs": []}}
    q = maneuvers.assess_match(
        trace_attributes, shortcut, "bicycle", cum[-1], len(venlo.points)
    )
    assert q.length_deviation < -0.10
    assert not q.is_usable


def test_route_length_honours_units():
    km = {"trip": {"units": "kilometers", "summary": {"length": 10.0}}}
    mi = {"trip": {"units": "miles", "summary": {"length": 10.0}}}
    assert maneuvers.route_length_m(km) == pytest.approx(10_000)
    assert maneuvers.route_length_m(mi) == pytest.approx(16_093.44)


# --- attribute spans ------------------------------------------------------


def test_spans_cover_the_track_in_order(trace_attributes, venlo):
    spans = maneuvers.attribute_spans(trace_attributes, len(venlo.points))
    assert spans
    assert all(s.end >= s.start for s in spans)
    assert all(b.start > a.start for a, b in zip(spans, spans[1:]))
    assert spans[-1].end == len(venlo.points) - 1


def test_spans_capture_unpaved_sections(trace_attributes, venlo):
    """Surface is the payload that matters most on a touring bike."""
    spans = maneuvers.attribute_spans(trace_attributes, len(venlo.points))
    surfaces = {s.surface for s in spans}
    assert "paved_smooth" in surfaces
    assert surfaces & {"gravel", "dirt", "compacted"}


def test_spans_capture_the_ferry(trace_attributes, venlo):
    """This route crosses the Maas by ferry -- a real navigational event."""
    spans = maneuvers.attribute_spans(trace_attributes, len(venlo.points))
    ferries = [s for s in spans if s.use == "ferry"]
    assert len(ferries) == 2


def test_ferry_appears_as_a_maneuver(mapped):
    assert sum(1 for m in mapped if m.type == "ferry") == 2


# --- costing selection ----------------------------------------------------


@pytest.mark.parametrize(
    "activity,expected",
    [
        ("e_touring_bicycle", "bicycle"),
        ("touring_bicycle", "bicycle"),
        ("mtb", "bicycle"),
        ("racebike", "bicycle"),
        ("hike", "pedestrian"),
        ("jogging", "pedestrian"),
        ("mountaineering", "pedestrian"),
        (None, "bicycle"),
    ],
)
def test_costing_selection(activity, expected):
    assert valhalla.costing_for(activity).name == expected


@pytest.mark.parametrize(
    "activity,bike",
    [("mtb", "Mountain"), ("e_racebike", "Road"), ("citybike", "City"), ("e_touring_bicycle", "Hybrid")],
)
def test_bicycle_type_selection(activity, bike):
    assert valhalla.costing_for(activity).options["bicycle_type"] == bike


# --- polyline decoding ----------------------------------------------------


def test_decode_polyline_uses_precision_six(trace_attributes):
    """Decoding at precision 5 would put this route in the wrong hemisphere."""
    shape = valhalla.decode_polyline(trace_attributes["shape"])
    lat, lon = shape[0]
    assert 51.3 < lat < 51.5
    assert 6.1 < lon < 6.3


def test_decode_polyline_rejects_truncated_input():
    with pytest.raises(valhalla.ValhallaError, match="truncated"):
        valhalla.decode_polyline("_p~iF~ps|U_")


def _polyline_length(encoded: str) -> float:
    shape = valhalla.decode_polyline(encoded)
    return sum(geo.haversine(a[0], a[1], b[0], b[1]) for a, b in zip(shape, shape[1:]))


def test_matched_route_shape_follows_the_original(trace_route, cum):
    """The matched path must be the path the rider actually planned."""
    assert _polyline_length(trace_route["trip"]["legs"][0]["shape"]) == pytest.approx(
        cum[-1], rel=0.02
    )


def test_attributes_shape_is_longer_because_edges_are_whole(trace_attributes, cum):
    """trace_attributes returns complete edges, not the clipped traced path.

    Its shape therefore overshoots at both ends (30.50 km against 28.83 km),
    which is why `assess_match` measures length from trace_route instead. This
    pins the distinction so a future change cannot quietly swap the two.
    """
    length = _polyline_length(trace_attributes["shape"])
    assert length > cum[-1] * 1.02
    assert length == pytest.approx(
        sum(e.get("length", 0.0) for e in trace_attributes["edges"]) * 1000, rel=0.02
    )
