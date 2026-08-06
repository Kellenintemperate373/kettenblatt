"""Orchestration: Komoot GPX in, navigation bundle out.

The map-matching stage is best-effort by design. If Valhalla is unreachable, or
matches the track badly, the bundle is still produced -- just without turn cues
and street names. The app treats those as optional, so a degraded bundle is
still a usable route rather than a failure.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from . import bundle as bundle_mod
from . import elevation, geo, gpx, maneuvers, valhalla


@dataclass
class MatchResult:
    quality: maneuvers.MatchQuality | None
    maneuvers: list
    spans: list
    warnings: list[str]


def _match_with(client: valhalla.Valhalla, points, cum, costing: valhalla.Costing):
    tr = client.trace_route(points, costing)
    ta = client.trace_attributes(points, costing)
    quality = maneuvers.assess_match(ta, tr, costing.name, cum[-1], len(points))
    return tr, ta, quality


def match(
    client: valhalla.Valhalla,
    points,
    cum: list[float],
    activity: str | None,
    log=lambda _msg: None,
) -> MatchResult:
    """Map-match the track, falling back to a more permissive costing if needed.

    Komoot routes over tracks and paths that bicycle costing penalises heavily.
    Where that makes the match unusable, pedestrian costing traverses almost
    anything and recovers the route, at the cost of slightly less apt phrasing.
    """
    warnings: list[str] = []

    primary = valhalla.costing_for(activity)
    log(f"matching with {primary.name} costing...")
    try:
        tr, ta, quality = _match_with(client, points, cum, primary)
    except valhalla.ValhallaError as e:
        warnings.append(f"map matching unavailable: {e}")
        return MatchResult(None, [], [], warnings)

    log(f"  {quality.summary()}")

    if not quality.is_usable and primary.name != valhalla.FALLBACK_COSTING.name:
        warnings.append(
            f"{primary.name} costing matched poorly "
            f"({quality.length_deviation:+.1%} length error); retried with "
            f"{valhalla.FALLBACK_COSTING.name}"
        )
        log(f"  poor match, retrying with {valhalla.FALLBACK_COSTING.name} costing...")
        try:
            tr2, ta2, quality2 = _match_with(client, points, cum, valhalla.FALLBACK_COSTING)
            log(f"  {quality2.summary()}")
            if abs(quality2.length_deviation) < abs(quality.length_deviation):
                tr, ta, quality = tr2, ta2, quality2
        except valhalla.ValhallaError as e:
            warnings.append(f"fallback costing failed: {e}")

    if not quality.is_usable:
        warnings.append(
            f"matched route length differs from the original by "
            f"{quality.length_deviation:+.1%}; turn cues omitted because they would "
            f"describe a different path"
        )
        return MatchResult(quality, [], maneuvers.attribute_spans(ta, len(points)), warnings)

    return MatchResult(
        quality=quality,
        maneuvers=maneuvers.map_maneuvers(tr, points, cum),
        spans=maneuvers.attribute_spans(ta, len(points)),
        warnings=warnings,
    )


def prepare(
    gpx_path: str | Path,
    client: valhalla.Valhalla | None = None,
    log=lambda _msg: None,
) -> bundle_mod.Bundle:
    """Parse, precompute and (optionally) enrich a route."""
    doc = gpx.parse(gpx_path)
    log(f'"{doc.name}" -- {len(doc.points)} points, activity {doc.activity or "unknown"}')
    if doc.dropped_duplicates:
        log(f"  dropped {doc.dropped_duplicates} duplicate points")

    cum = geo.cumulative_distances(doc.points)
    raw_ele = elevation.fill_missing([p.ele for p in doc.points])
    smoothed = elevation.smooth(raw_ele, cum)
    cum_ascent = elevation.cumulative_ascent(smoothed)
    log(f"  {cum[-1] / 1000:.2f} km, {cum_ascent[-1]:.0f} m ascent")

    result = MatchResult(None, [], [], [])
    reverse_maneuvers: list = []
    if client is not None:
        result = match(client, doc.points, cum, doc.activity, log=log)
        if result.maneuvers:
            log(
                f"  {len(result.maneuvers)} turn cues, "
                f"{len(result.spans)} surface spans"
            )

        # Match the other direction too, so riding a loop backwards keeps full
        # guidance. Mirroring the forward cues cannot work: ridden the other way
        # you meet each junction from a different arm, turn the other way, and
        # join a different street. Only a fresh match knows that.
        reverse_maneuvers = match_reverse(client, doc.points, doc.activity, log=log)

    for w in result.warnings:
        log(f"  WARNING: {w}")

    return bundle_mod.Bundle(
        name=doc.name,
        activity=doc.activity,
        points=[(p.lat, p.lon, e) for p, e in zip(doc.points, smoothed)],
        cum_dist_m=cum,
        cum_ascent_m=cum_ascent,
        maneuvers=result.maneuvers,
        reverse_maneuvers=reverse_maneuvers,
        surfaces=result.spans,
        waypoints=doc.waypoints,
        match_quality=result.quality.as_dict() if result.quality else None,
        warnings=result.warnings,
    )


def match_reverse(
    client: valhalla.Valhalla,
    points,
    activity: str | None,
    log=lambda _msg: None,
) -> list:
    """Turn cues for the route ridden backwards.

    The returned indices are in **reversed order** -- index 0 is the original
    finish. The app applies them as-is after flipping the geometry, so neither
    side has to translate between index spaces.
    """
    flipped = list(reversed(points))
    cum = geo.cumulative_distances(flipped)

    log("matching the reverse direction...")
    try:
        result = match(client, flipped, cum, activity, log=log)
    except valhalla.ValhallaError as e:
        log(f"  WARNING: reverse matching failed: {e}")
        return []

    if result.maneuvers:
        log(f"  {len(result.maneuvers)} reverse turn cues")
    else:
        log("  no usable reverse cues; riding backwards will have none")
    return result.maneuvers
