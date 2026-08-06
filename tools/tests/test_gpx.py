"""Parser and elevation tests, anchored on the real Komoot export."""

from __future__ import annotations

import math
import random
from pathlib import Path

import pytest

from navi import elevation, geo, gpx

FIXTURE = Path(__file__).parent / "fixtures" / "venlo.gpx"


@pytest.fixture(scope="module")
def venlo():
    return gpx.parse(FIXTURE)


def test_parses_komoot_metadata(venlo):
    assert venlo.name == "Fahrradtour Venlo - Blaue Lagune"
    assert venlo.activity == "e_touring_bicycle"


def test_track_point_count(venlo):
    # 606 points in the file; the parser drops nothing here because no two
    # consecutive points coincide.
    assert len(venlo.points) == 606
    assert venlo.dropped_duplicates == 0


def test_reads_elevation_and_time(venlo):
    first = venlo.points[0]
    assert first.lat == pytest.approx(51.381706)
    assert first.lon == pytest.approx(6.216661)
    assert first.ele == pytest.approx(40.299038)
    assert first.time == "2026-07-31T19:43:33.735Z"


def test_waypoint_with_symbol(venlo):
    assert len(venlo.waypoints) == 1
    w = venlo.waypoints[0]
    assert w.name == "Eissalon Clevers Grubbenvorst"
    assert w.sym == "Restaurant"
    assert w.lat == pytest.approx(51.419667)


def test_route_is_a_closed_loop(venlo):
    first, last = venlo.points[0], venlo.points[-1]
    assert (first.lat, first.lon) == (last.lat, last.lon)


def test_total_distance(venlo):
    cum = geo.cumulative_distances(venlo.points)
    assert cum[0] == 0.0
    assert cum[-1] / 1000 == pytest.approx(28.83, abs=0.05)


def test_namespace_agnostic_parsing(tmp_path):
    """A GPX 1.0 file with a different prefix must parse identically."""
    src = FIXTURE.read_text()
    # Re-declare the default namespace under an explicit prefix.
    swapped = src.replace(
        'xmlns="http://www.topografix.com/GPX/1/1"',
        'xmlns:g="http://www.topografix.com/GPX/1/1"',
    )
    swapped = swapped.replace("<gpx ", "<g:gpx ").replace("</gpx>", "</g:gpx>")
    for tag in ("metadata", "name", "author", "link", "text", "type", "wpt", "sym",
                "trk", "trkseg", "trkpt", "ele", "time"):
        swapped = swapped.replace(f"<{tag}>", f"<g:{tag}>").replace(f"</{tag}>", f"</g:{tag}>")
        swapped = swapped.replace(f"<{tag} ", f"<g:{tag} ")
    p = tmp_path / "prefixed.gpx"
    p.write_text(swapped)

    parsed = gpx.parse(p)
    assert len(parsed.points) == 606
    assert parsed.activity == "e_touring_bicycle"
    assert parsed.waypoints[0].sym == "Restaurant"


def test_rejects_degenerate_file(tmp_path):
    p = tmp_path / "empty.gpx"
    p.write_text(
        '<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1">'
        "<trk><trkseg><trkpt lat=\"1\" lon=\"1\"/></trkseg></trk></gpx>"
    )
    with pytest.raises(ValueError, match="at least 2 track points"):
        gpx.parse(p)


def test_falls_back_to_route_points(tmp_path):
    p = tmp_path / "rte.gpx"
    p.write_text(
        '<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1">'
        "<rte><name>Planned</name>"
        '<rtept lat="51.0" lon="6.0"><ele>10</ele></rtept>'
        '<rtept lat="51.001" lon="6.001"><ele>12</ele></rtept>'
        "</rte></gpx>"
    )
    parsed = gpx.parse(p)
    assert parsed.name == "Planned"
    assert len(parsed.points) == 2


def test_drops_consecutive_duplicate_points(tmp_path):
    p = tmp_path / "dupes.gpx"
    p.write_text(
        '<?xml version="1.0"?><gpx xmlns="http://www.topografix.com/GPX/1/1">'
        "<trk><trkseg>"
        '<trkpt lat="51.0" lon="6.0"/>'
        '<trkpt lat="51.0" lon="6.0"/>'
        '<trkpt lat="51.001" lon="6.001"/>'
        "</trkseg></trk></gpx>"
    )
    parsed = gpx.parse(p)
    assert len(parsed.points) == 2
    assert parsed.dropped_duplicates == 1


# --- elevation ------------------------------------------------------------


def test_threshold_rejects_accumulated_noise(venlo):
    """The threshold, not the smoothing, is what makes ascent believable here.

    Summing every positive delta claims 68 m on a route with only 32 m of
    relief that descends into the Maas valley and climbs back out once.
    """
    cum = geo.cumulative_distances(venlo.points)
    raw = elevation.fill_missing([p.ele for p in venlo.points])
    smoothed = elevation.smooth(raw, cum)

    assert elevation.cumulative_ascent(raw, threshold_m=0.0)[-1] == pytest.approx(68.2, abs=1.0)
    assert elevation.cumulative_ascent(smoothed)[-1] == pytest.approx(45.0, abs=2.0)


def test_smoothing_preserves_a_clean_profile(venlo):
    """On planned-route data, smoothing must not distort the answer.

    Komoot derives elevation from a terrain model, so it is already clean and
    the window size should barely matter. Sensitivity to the window here would
    mean smoothing had started eating real terrain.
    """
    cum = geo.cumulative_distances(venlo.points)
    raw = elevation.fill_missing([p.ele for p in venlo.points])

    totals = [
        elevation.cumulative_ascent(elevation.smooth(raw, cum, w))[-1]
        for w in (30.0, 60.0, 100.0, 300.0)
    ]
    assert max(totals) - min(totals) < 3.0


def _synthetic_climb(noise):
    """A steady 99.5 m climb sampled every 10 m, plus a noise function."""
    n = 200
    cum = [10.0 * i for i in range(n)]
    clean = [100.0 + 0.5 * i for i in range(n)]
    return cum, clean, [e + noise(i) for i, e in enumerate(clean)]


def test_smoothing_recovers_ascent_under_random_noise():
    """Where smoothing earns its place: a recorded track with GPS jitter.

    The +/-2 m noise never clears the 3 m threshold on its own, but it rides on
    a real gradient, so the threshold alone lets it through and the total runs
    away to 143 m against a 99.5 m truth.
    """
    random.seed(7)
    cum, clean, noisy = _synthetic_climb(lambda i: random.uniform(-2.0, 2.0))
    true_ascent = clean[-1] - clean[0]

    assert elevation.cumulative_ascent(noisy)[-1] > true_ascent + 40
    smoothed = elevation.cumulative_ascent(elevation.smooth(noisy, cum))[-1]
    assert smoothed == pytest.approx(true_ascent, abs=5.0)


def test_smoothing_survives_worst_case_sawtooth():
    """Noise alternating every sample is the hardest case for a moving average.

    This is what set the 60 m default: a 30 m window leaves 126 m of the 450 m
    unsmoothed error in place, because at 10 m spacing it spans too few samples
    to cancel a signal flipping at every one.
    """
    cum, clean, saw = _synthetic_climb(lambda i: 2.0 if i % 2 else -2.0)
    true_ascent = clean[-1] - clean[0]

    assert elevation.cumulative_ascent(saw)[-1] > 400
    narrow = elevation.cumulative_ascent(elevation.smooth(saw, cum, 30.0))[-1]
    default = elevation.cumulative_ascent(elevation.smooth(saw, cum))[-1]

    assert narrow > true_ascent + 20
    assert default == pytest.approx(true_ascent, abs=5.0)


def test_cumulative_ascent_is_monotonic(venlo):
    cum = geo.cumulative_distances(venlo.points)
    smoothed = elevation.smooth(elevation.fill_missing([p.ele for p in venlo.points]), cum)
    asc = elevation.cumulative_ascent(smoothed)
    assert all(b >= a for a, b in zip(asc, asc[1:]))
    assert len(asc) == len(venlo.points)


def test_ascent_threshold_ignores_small_bumps():
    # A 2 m wobble repeated many times must not accumulate.
    eles = [100.0, 102.0, 100.0, 102.0, 100.0, 102.0]
    assert elevation.cumulative_ascent(eles, threshold_m=3.0)[-1] == 0.0
    # A genuine 10 m climb is credited in full.
    assert elevation.cumulative_ascent([100.0, 110.0], threshold_m=3.0)[-1] == pytest.approx(10.0)


def test_fill_missing_handles_all_none():
    assert elevation.fill_missing([None, None]) == [0.0, 0.0]
    assert elevation.fill_missing([None, 5.0, None]) == [5.0, 5.0, 5.0]


# --- geometry -------------------------------------------------------------


def test_haversine_against_known_distance():
    # 1 degree of latitude is ~111.2 km.
    assert geo.haversine(51.0, 6.0, 52.0, 6.0) == pytest.approx(111_195, rel=1e-3)


def test_bearing_cardinals():
    assert geo.bearing(51.0, 6.0, 52.0, 6.0) == pytest.approx(0.0, abs=0.1)
    assert geo.bearing(51.0, 6.0, 51.0, 7.0) == pytest.approx(90.0, abs=0.5)
    assert geo.bearing(51.0, 6.0, 50.0, 6.0) == pytest.approx(180.0, abs=0.1)


def test_bearing_delta_wraps():
    assert geo.bearing_delta(350.0, 10.0) == pytest.approx(20.0)
    assert geo.bearing_delta(10.0, 350.0) == pytest.approx(20.0)
    assert geo.bearing_delta(0.0, 180.0) == pytest.approx(180.0)


def test_point_to_segment_projects_and_clamps():
    # Perpendicular offset from the middle of a horizontal segment.
    t, d, _ = geo.point_to_segment(5.0, 3.0, 0.0, 0.0, 10.0, 0.0)
    assert t == pytest.approx(0.5)
    assert d == pytest.approx(3.0)
    # Beyond the far end, clamped to t=1.
    t, d, _ = geo.point_to_segment(20.0, 0.0, 0.0, 0.0, 10.0, 0.0)
    assert t == pytest.approx(1.0)
    assert d == pytest.approx(10.0)


def test_point_to_segment_handles_zero_length():
    t, d, _ = geo.point_to_segment(3.0, 4.0, 1.0, 1.0, 1.0, 1.0)
    assert t == 0.0
    assert d == pytest.approx(math.hypot(2.0, 3.0))


def test_nearest_point_index_respects_search_range(venlo):
    """The overlapping legs of this loop must not cross-bind when range-limited."""
    pts = venlo.points
    # The final points retrace the first ones exactly. Searching the whole track
    # for the last point finds the coincident one at the start...
    target = pts[-1]
    i_all, d_all = geo.nearest_point_index(pts, target.lat, target.lon)
    assert d_all == pytest.approx(0.0, abs=0.01)
    assert i_all == 0
    # ...but restricting the range forces the correct, later match.
    i_late, d_late = geo.nearest_point_index(pts, target.lat, target.lon, start=500)
    assert d_late == pytest.approx(0.0, abs=0.01)
    assert i_late >= 500


def test_nearest_point_index_rejects_empty_range(venlo):
    with pytest.raises(ValueError):
        geo.nearest_point_index(venlo.points, 51.0, 6.0, start=10, end=10)
