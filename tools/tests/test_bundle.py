"""Bundle assembly, the end-to-end pipeline, and MBTiles construction."""

from __future__ import annotations

import gzip
import json
import math
import sqlite3
from pathlib import Path

import pytest

from navi import bundle as bundle_mod
from navi import elevation, geo, gpx, maneuvers, pipeline, tiles, valhalla

FIXTURES = Path(__file__).parent / "fixtures"


class FrozenValhalla:
    """Replays the recorded responses, so the pipeline is testable offline."""

    def __init__(self, route=None, attrs=None, fail=False):
        self._route = route
        self._attrs = attrs
        self._fail = fail

    def _load(self, name):
        with gzip.open(FIXTURES / f"venlo_{name}.json.gz", "rt") as f:
            return json.load(f)

    def status(self):
        if self._fail:
            raise valhalla.ValhallaError("connection refused")
        return {"version": "test"}

    def trace_route(self, points, costing):
        if self._fail:
            raise valhalla.ValhallaError("connection refused")
        return self._route if self._route is not None else self._load("trace_route")

    def trace_attributes(self, points, costing):
        if self._fail:
            raise valhalla.ValhallaError("connection refused")
        return self._attrs if self._attrs is not None else self._load("trace_attributes")


@pytest.fixture(scope="module")
def venlo_gpx():
    return FIXTURES / "venlo.gpx"


@pytest.fixture(scope="module")
def built(venlo_gpx):
    return pipeline.prepare(venlo_gpx, client=FrozenValhalla())


# --- pipeline -------------------------------------------------------------


def test_prepare_produces_a_complete_bundle(built):
    assert built.name == "Fahrradtour Venlo - Blaue Lagune"
    assert built.activity == "e_touring_bicycle"
    assert len(built.points) == 606
    assert built.distance_m == pytest.approx(28_833, abs=10)
    assert len(built.maneuvers) == 70
    assert len(built.waypoints) == 1
    assert not built.warnings


def test_parallel_arrays_stay_aligned(built):
    n = len(built.points)
    assert len(built.cum_dist_m) == n
    assert len(built.cum_ascent_m) == n


def test_indices_address_real_points(built):
    n = len(built.points)
    assert all(0 <= m.idx < n for m in built.maneuvers)
    assert all(0 <= s.start <= s.end < n for s in built.surfaces)


def test_prepare_degrades_gracefully_without_valhalla(venlo_gpx):
    """An unreachable server must still yield a navigable route."""
    b = pipeline.prepare(venlo_gpx, client=FrozenValhalla(fail=True))
    assert len(b.points) == 606
    assert b.distance_m == pytest.approx(28_833, abs=10)
    assert b.maneuvers == []
    assert any("unavailable" in w for w in b.warnings)


def test_prepare_without_a_client_skips_matching(venlo_gpx):
    b = pipeline.prepare(venlo_gpx, client=None)
    assert b.maneuvers == []
    assert b.match_quality is None
    assert len(b.points) == 606


def test_shortcutting_match_drops_cues_but_keeps_the_route(venlo_gpx):
    """The 15%-short match must not contribute turn cues.

    Its maneuvers describe a path the rider will not take, so emitting them
    would be worse than emitting nothing.
    """
    with gzip.open(FIXTURES / "venlo_trace_route.json.gz", "rt") as f:
        bad = json.load(f)
    bad["trip"]["summary"]["length"] = 24.581

    b = pipeline.prepare(venlo_gpx, client=FrozenValhalla(route=bad))
    assert b.maneuvers == []
    assert b.surfaces, "attribution survives even when maneuvers do not"
    assert any("differs from the original" in w for w in b.warnings)
    assert len(b.points) == 606


# --- serialisation --------------------------------------------------------


def test_bundle_round_trips_through_json(built, tmp_path):
    out = built.write(tmp_path / "r.navi.json")
    d = json.loads(out.read_text())

    assert d["version"] == bundle_mod.BUNDLE_VERSION
    assert len(d["points"]) == len(built.points)
    assert d["maneuvers"][0]["idx"] == built.maneuvers[0].idx
    assert d["waypoints"][0]["name"] == "Eissalon Clevers Grubbenvorst"
    assert d["waypoints"][0]["sym"] == "Restaurant"


def test_coordinates_are_rounded_not_truncated(built, tmp_path):
    d = json.loads(built.write(tmp_path / "r.navi.json").read_text())
    for lat, lon, ele in d["points"][:50]:
        assert len(str(lat).split(".")[-1]) <= bundle_mod.COORD_DP
        assert len(str(lon).split(".")[-1]) <= bundle_mod.COORD_DP
        assert len(str(ele).split(".")[-1]) <= bundle_mod.ELEVATION_DP
    # Rounding must not move a point meaningfully.
    assert d["points"][0][0] == pytest.approx(built.points[0][0], abs=1e-6)


def test_bundle_stays_small(built, tmp_path):
    """It gets transferred to a phone, so size is a feature."""
    size = built.write(tmp_path / "r.navi.json").stat().st_size
    assert size < 100 * 1024


def test_bbox_contains_every_point(built):
    min_lat, min_lon, max_lat, max_lon = built.bbox()
    for lat, lon, _ in built.points:
        assert min_lat - 1e-6 <= lat <= max_lat + 1e-6
        assert min_lon - 1e-6 <= lon <= max_lon + 1e-6


def test_optional_fields_are_omitted_when_empty(tmp_path):
    b = bundle_mod.Bundle(
        name="bare", activity=None,
        points=[(51.0, 6.0, 10.0), (51.001, 6.001, 11.0)],
        cum_dist_m=[0.0, 130.0], cum_ascent_m=[0.0, 0.0],
    )
    d = json.loads(b.write(tmp_path / "b.navi.json").read_text())
    assert "matchQuality" not in d
    assert "warnings" not in d
    assert d["maneuvers"] == []


def test_output_paths(tmp_path):
    assert bundle_mod.output_path("routes/trip.gpx").name == "trip.navi.json"
    assert bundle_mod.tiles_path("routes/trip.gpx").name == "trip.mbtiles"
    assert bundle_mod.output_path("routes/trip.gpx", tmp_path).parent == tmp_path
    # Dots inside the name must survive.
    assert bundle_mod.output_path("a.v2.gpx").name == "a.v2.navi.json"
    assert bundle_mod.tiles_path("a.v2.gpx").name == "a.v2.mbtiles"


# --- tiles ----------------------------------------------------------------


def test_deg2tile_known_values():
    # Zoom 0 is a single tile.
    assert tiles.deg2tile(51.4, 6.2, 0) == (0, 0)
    # Null Island sits at the top-left of the bottom-right quadrant at zoom 1.
    assert tiles.deg2tile(0.0, 0.0, 1) == (1, 1)
    # Venlo at zoom 12, computed from the slippy-map formula directly.
    n = 2**12
    expected = (
        int((6.2167 + 180.0) / 360.0 * n),
        int((1.0 - math.asinh(math.tan(math.radians(51.3817))) / math.pi) / 2.0 * n),
    )
    assert tiles.deg2tile(51.3817, 6.2167, 12) == expected == (2118, 1364)


def test_deg2tile_clamps_to_valid_range():
    for z in (1, 5, 12):
        n = 2**z
        for lat, lon in ((89.9, 179.9), (-89.9, -179.9)):
            x, y = tiles.deg2tile(lat, lon, z)
            assert 0 <= x < n and 0 <= y < n


def test_corridor_wins_on_a_long_linear_route():
    """Where the corridor pays off: a point-to-point that crosses a big box.

    A 127 km diagonal needs about a tenth of its bounding box.
    """
    pts = [(51.0 + 0.01 * i, 6.0 + 0.005 * i, 0.0) for i in range(110)]
    corridor = tiles.corridor_tiles(pts, 14, tiles.DEFAULT_BUFFER_M)
    box = tiles.bbox_tiles(pts, 14)

    assert len(corridor) < len(box) * 0.2
    assert tiles.tiles_for(pts, 14, tiles.DEFAULT_BUFFER_M)[1] == "corridor"


def test_bbox_wins_on_a_compact_loop(built):
    """And where it does not: this route folds back through a small area.

    A 500 m corridor around 29 km of track costs more than the 7 x 5 km box
    containing it, so `tiles_for` must take the box -- which also fills in the
    ground enclosed by the loop.
    """
    corridor = tiles.corridor_tiles(built.points, 16, tiles.DEFAULT_BUFFER_M)
    box = tiles.bbox_tiles(built.points, 16)

    assert len(corridor) > len(box)
    chosen, shape = tiles.tiles_for(built.points, 16, tiles.DEFAULT_BUFFER_M)
    assert shape == "bbox"
    assert chosen == box


def test_tiles_for_never_exceeds_either_shape(built):
    for z in (12, 14, 16):
        chosen, _ = tiles.tiles_for(built.points, z, tiles.DEFAULT_BUFFER_M)
        assert len(chosen) <= len(tiles.corridor_tiles(built.points, z, tiles.DEFAULT_BUFFER_M))
        assert len(chosen) <= len(tiles.bbox_tiles(built.points, z))


def test_corridor_covers_every_track_point(built):
    corridor = tiles.corridor_tiles(built.points, 14, tiles.DEFAULT_BUFFER_M)
    for lat, lon, _ in built.points:
        assert tiles.deg2tile(lat, lon, 14) in corridor


def test_corridor_grows_with_buffer(built):
    narrow = tiles.corridor_tiles(built.points, 15, 200.0)
    wide = tiles.corridor_tiles(built.points, 15, 2000.0)
    assert narrow < wide


def test_tile_width_shrinks_with_zoom():
    assert tiles.tile_width_m(51.4, 12) > tiles.tile_width_m(51.4, 16)
    # A zoom-16 tile near Venlo is a few hundred metres across.
    assert 300 < tiles.tile_width_m(51.4, 16) < 450


def test_mbtiles_written_with_tms_row_order(tmp_path, monkeypatch):
    """The y-flip that decides whether the map renders or comes up blank.

    MBTiles counts rows from the bottom; slippy-map y counts from the top.
    """
    fake_png = b"\x89PNG\r\n\x1a\n" + b"x" * 32
    monkeypatch.setattr(tiles, "_fetch", lambda url: fake_png)

    b = bundle_mod.Bundle(
        name="tiny", activity=None,
        points=[(51.3817, 6.2167, 40.0), (51.3820, 6.2170, 40.0)],
        cum_dist_m=[0.0, 40.0], cum_ascent_m=[0.0, 0.0],
    )
    out = tiles.build(b, tmp_path / "t.mbtiles", zoom_min=12, zoom_max=12,
                      buffer_m=0.0, confirm=False)

    conn = sqlite3.connect(out)
    try:
        rows = conn.execute("SELECT zoom_level, tile_column, tile_row FROM tiles").fetchall()
        meta = dict(conn.execute("SELECT name, value FROM metadata").fetchall())
    finally:
        conn.close()

    assert rows
    xyz_y = tiles.deg2tile(51.3817, 6.2167, 12)[1]
    stored_rows = {r[2] for r in rows}
    assert (2**12 - 1) - xyz_y in stored_rows
    assert xyz_y not in stored_rows, "stored in XYZ order; the map would render blank"

    assert meta["format"] == "png"
    assert meta["minzoom"] == "12" and meta["maxzoom"] == "12"
    assert "OpenTopoMap" in meta["attribution"]


def test_mbtiles_bounds_metadata_is_lon_lat_order(tmp_path, monkeypatch):
    """The MBTiles spec orders bounds as left,bottom,right,top."""
    monkeypatch.setattr(tiles, "_fetch", lambda url: b"png")
    b = bundle_mod.Bundle(
        name="t", activity=None,
        points=[(51.30, 6.10, 0.0), (51.40, 6.20, 0.0)],
        cum_dist_m=[0.0, 1.0], cum_ascent_m=[0.0, 0.0],
    )
    out = tiles.build(b, tmp_path / "t.mbtiles", zoom_min=10, zoom_max=10,
                      buffer_m=0.0, confirm=False)
    conn = sqlite3.connect(out)
    try:
        bounds = dict(conn.execute("SELECT name, value FROM metadata").fetchall())["bounds"]
    finally:
        conn.close()
    left, bottom, right, top = (float(v) for v in bounds.split(","))
    assert (left, bottom, right, top) == pytest.approx((6.10, 51.30, 6.20, 51.40))


def test_tile_source_validation(built, tmp_path):
    with pytest.raises(ValueError, match="unknown tile source"):
        tiles.build(built, tmp_path / "x.mbtiles", source="nope", confirm=False)
    with pytest.raises(ValueError, match="needs an API key"):
        tiles.build(built, tmp_path / "x.mbtiles", source="thunderforest-outdoors",
                    confirm=False)


def _counting_fetch(monkeypatch):
    """Replace the network with a counter, so tests can assert what was fetched."""
    calls = []

    def fake(url):
        calls.append(url)
        return b"\x89PNG\r\n\x1a\n" + b"x" * 16

    monkeypatch.setattr(tiles, "_fetch", fake)
    return calls


def _tiny_bundle():
    return bundle_mod.Bundle(
        name="resume", activity=None,
        points=[(51.3817, 6.2167, 40.0), (51.3900, 6.2300, 40.0)],
        cum_dist_m=[0.0, 1200.0], cum_ascent_m=[0.0, 0.0],
    )


def test_second_run_fetches_only_missing_tiles(tmp_path, monkeypatch):
    """An interrupted download must not start again from zero."""
    out = tmp_path / "r.mbtiles"
    b = _tiny_bundle()

    first = _counting_fetch(monkeypatch)
    tiles.build(b, out, zoom_min=12, zoom_max=16, buffer_m=300.0, confirm=False)
    assert len(first) > 4, "need enough tiles for the partial-delete to be meaningful"

    # Drop two tiles, as an interrupted run would have left them.
    conn = sqlite3.connect(out)
    try:
        victims = conn.execute(
            "SELECT zoom_level, tile_column, tile_row FROM tiles LIMIT 2"
        ).fetchall()
        conn.executemany(
            "DELETE FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", victims
        )
        conn.commit()
    finally:
        conn.close()

    second = _counting_fetch(monkeypatch)
    tiles.build(b, out, zoom_min=12, zoom_max=16, buffer_m=300.0, confirm=False)

    assert len(second) == 2, f"refetched {len(second)} tiles, expected the 2 missing ones"

    conn = sqlite3.connect(out)
    try:
        assert conn.execute("SELECT count(*) FROM tiles").fetchone()[0] == len(first)
    finally:
        conn.close()


def test_a_complete_pack_needs_no_downloads(tmp_path, monkeypatch):
    out = tmp_path / "r.mbtiles"
    b = _tiny_bundle()

    _counting_fetch(monkeypatch)
    tiles.build(b, out, zoom_min=12, zoom_max=12, buffer_m=300.0, confirm=False)

    again = _counting_fetch(monkeypatch)
    tiles.build(b, out, zoom_min=12, zoom_max=12, buffer_m=300.0, confirm=False)
    assert again == []


def test_switching_tile_source_discards_the_old_pack(tmp_path, monkeypatch):
    """Two styles in one pack would change appearance mid-ride."""
    out = tmp_path / "r.mbtiles"
    b = _tiny_bundle()

    _counting_fetch(monkeypatch)
    tiles.build(b, out, source="opentopomap", zoom_min=12, zoom_max=12,
                buffer_m=300.0, confirm=False)

    switched = _counting_fetch(monkeypatch)
    tiles.build(b, out, source="thunderforest-outdoors", api_key="k",
                zoom_min=12, zoom_max=12, buffer_m=300.0, confirm=False)

    assert switched, "should have refetched everything for the new source"
    conn = sqlite3.connect(out)
    try:
        meta = dict(conn.execute("SELECT name, value FROM metadata").fetchall())
    finally:
        conn.close()
    assert "Thunderforest" in meta["attribution"]


def test_corrupt_pack_is_replaced(tmp_path, monkeypatch):
    out = tmp_path / "r.mbtiles"
    out.write_bytes(b"not a database")

    calls = _counting_fetch(monkeypatch)
    tiles.build(_tiny_bundle(), out, zoom_min=12, zoom_max=12, buffer_m=300.0, confirm=False)
    assert calls
    conn = sqlite3.connect(out)
    try:
        assert conn.execute("SELECT count(*) FROM tiles").fetchone()[0] > 0
    finally:
        conn.close()


def test_tile_urls_rotate_subdomains():
    src = tiles.SOURCES["opentopomap"]
    urls = {src.tile_url(12, x, 100, None) for x in range(6)}
    assert len({u.split("//")[1][0] for u in urls}) == 3


def test_thunderforest_url_carries_the_key():
    url = tiles.SOURCES["thunderforest-outdoors"].tile_url(12, 1, 2, "SECRET")
    assert "apikey=SECRET" in url
