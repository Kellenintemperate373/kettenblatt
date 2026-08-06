"""Elevation smoothing and ascent accumulation.

Accumulating every positive elevation delta overstates ascent badly. Two
different corrections are needed, and each covers a case the other misses:

* The **threshold** handles Komoot's planned routes, whose elevation comes from
  a terrain model and is already clean. On the Venlo fixture it takes the total
  from 68 m to a believable ~44 m, and smoothing barely moves it -- the figure
  stays within ~3 m across every window from 30 m to 300 m.

* The **smoothing window** handles *recorded* tracks, where GPS and barometric
  elevation genuinely jitter. There the threshold alone is not enough, because
  the noise rides on a real gradient and so keeps clearing the threshold: a
  synthetic 99.5 m climb sampled every 10 m accumulates to 450 m unsmoothed.

The 60 m default was measured, not guessed. A 30 m window suppresses realistic
random noise (100.9 m against a 99.5 m truth) but not a worst-case alternating
sawtooth (126.5 m); 60 m handles both (98.9 m and 97.8 m). Windows past ~100 m
begin shaving real ascent off steep ground without rejecting more noise.
"""

from __future__ import annotations

# Points within this distance along the track are averaged together.
SMOOTHING_WINDOW_M = 60.0
# A climb must exceed this before it counts, which rejects residual noise.
ASCENT_THRESHOLD_M = 3.0


def fill_missing(elevations: list[float | None]) -> list[float]:
    """Replace missing elevations by carrying neighbouring values inward.

    A track with no elevation at all flattens to zero, which keeps downstream
    maths total without needing a null check at every use.
    """
    known = [e for e in elevations if e is not None]
    if not known:
        return [0.0] * len(elevations)

    out: list[float] = []
    last = known[0]
    for e in elevations:
        if e is None:
            out.append(last)
        else:
            out.append(e)
            last = e
    return out


def smooth(elevations: list[float], cum_dist: list[float], window_m: float = SMOOTHING_WINDOW_M) -> list[float]:
    """Average elevations over a sliding window measured in metres along the track.

    A distance window rather than a point-count window keeps the smoothing
    consistent where point spacing varies (this route ranges from 1 m to 517 m
    between consecutive points).
    """
    n = len(elevations)
    if n == 0:
        return []
    half = window_m / 2.0

    out = [0.0] * n
    lo = hi = 0
    running = 0.0
    for i in range(n):
        target_lo, target_hi = cum_dist[i] - half, cum_dist[i] + half
        while hi < n and cum_dist[hi] <= target_hi:
            running += elevations[hi]
            hi += 1
        while cum_dist[lo] < target_lo:
            running -= elevations[lo]
            lo += 1
        out[i] = running / (hi - lo)
    return out


def cumulative_ascent(elevations: list[float], threshold_m: float = ASCENT_THRESHOLD_M) -> list[float]:
    """Running total of ascent, ignoring climbs smaller than `threshold_m`.

    Tracks a reference elevation that ratchets: it follows the track down freely
    but only moves up once a climb has proven itself larger than the threshold,
    at which point the whole climb is credited at once.
    """
    if not elevations:
        return []

    out = [0.0] * len(elevations)
    ascent = 0.0
    ref = elevations[0]
    for i in range(1, len(elevations)):
        e = elevations[i]
        if e > ref + threshold_m:
            ascent += e - ref
            ref = e
        elif e < ref:
            ref = e
        out[i] = ascent
    return out
