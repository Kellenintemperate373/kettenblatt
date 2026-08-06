#!/usr/bin/env python3
"""Turn a Komoot GPX export into a navigation bundle for the Android app.

    ./prep.py "routes/Fahrradtour Venlo - Blaue Lagune.gpx"
    ./prep.py routes/*.gpx --out build/ --tiles

Needs the local Valhalla running for turn cues and street names:

    docker compose -f tools/docker-compose.yml up -d

Without it, `--no-match` produces a geometry-and-elevation bundle that the app
still navigates from -- just with no turn banner.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from navi import bundle as bundle_mod  # noqa: E402
from navi import pipeline, tiles, valhalla  # noqa: E402


def log(msg: str) -> None:
    print(msg, file=sys.stderr)


def expand_inputs(paths: list[Path]) -> tuple[list[Path], list[Path]]:
    """Resolve arguments to a list of GPX files, expanding any folders.

    Returns (files, missing). Folders are scanned non-recursively -- a routes
    directory is a flat drop-box, not a tree to crawl.
    """
    files: list[Path] = []
    missing: list[Path] = []

    for path in paths:
        if path.is_dir():
            files.extend(sorted(p for p in path.iterdir() if p.suffix.lower() == ".gpx"))
        elif path.exists():
            files.append(path)
        else:
            missing.append(path)

    # A folder and an explicit file can name the same route.
    seen: set[Path] = set()
    unique = [f for f in files if not (f.resolve() in seen or seen.add(f.resolve()))]
    return unique, missing


def is_up_to_date(gpx_path: Path, bundle_path: Path) -> bool:
    """True when the bundle already reflects this GPX.

    Compares modification times so a folder run only does the new arrivals; the
    Valhalla round-trip is the slow part and repeating it changes nothing.
    """
    if not bundle_path.exists():
        return False
    return bundle_path.stat().st_mtime >= gpx_path.stat().st_mtime


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Komoot GPX -> .navi.json navigation bundle",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument(
        "gpx",
        nargs="+",
        type=Path,
        help="Komoot GPX export(s), or a folder of them",
    )
    ap.add_argument(
        "-f",
        "--force",
        action="store_true",
        help="re-prepare routes whose bundle is already newer than the GPX",
    )
    ap.add_argument("-o", "--out", type=Path, help="output directory (default: alongside the input)")
    ap.add_argument("--valhalla", default=valhalla.DEFAULT_BASE_URL, help="Valhalla base URL")
    ap.add_argument(
        "--no-match",
        action="store_true",
        help="skip map matching entirely (no turn cues or street names)",
    )
    ap.add_argument(
        "--tiles",
        action="store_true",
        help="also build an offline .mbtiles pack for the route corridor",
    )
    ap.add_argument(
        "--tile-source",
        default=tiles.DEFAULT_SOURCE,
        choices=sorted(tiles.SOURCES),
        help="tile source for --tiles (default: %(default)s)",
    )
    ap.add_argument("--tile-zoom", default="12-16", help="zoom range for --tiles (default: %(default)s)")
    ap.add_argument(
        "--tile-buffer",
        type=float,
        default=tiles.DEFAULT_BUFFER_M,
        help="corridor half-width in metres for --tiles (default: %(default)s)",
    )
    ap.add_argument("--tile-api-key", default=None, help="API key, for sources that need one")
    ap.add_argument("-y", "--yes", action="store_true", help="skip the tile download confirmation")
    args = ap.parse_args()

    client = None
    if not args.no_match:
        client = valhalla.Valhalla(args.valhalla)
        try:
            client.status()
        except valhalla.ValhallaError as e:
            log(f"WARNING: {e}")
            log("         continuing without turn cues; pass --no-match to silence this.\n")
            client = None

    try:
        zoom_min, zoom_max = (int(z) for z in args.tile_zoom.split("-", 1))
    except ValueError:
        ap.error(f"--tile-zoom must look like 12-16, got {args.tile_zoom!r}")

    inputs, missing = expand_inputs(args.gpx)
    for path in missing:
        log(f"ERROR: {path} does not exist")
    failures = len(missing)

    if not inputs:
        log("nothing to do")
        return 1 if failures else 0

    log(f"{len(inputs)} route(s) to prepare")

    for path in inputs:
        out_path = bundle_mod.output_path(path, args.out)
        if not args.force and is_up_to_date(path, out_path):
            log(f"\n{path.name}\n  up to date, skipping (use --force to redo)")
            continue

        log(f"\n{path.name}")
        try:
            b = pipeline.prepare(path, client=client, log=log)
        except Exception as e:  # noqa: BLE001 - one bad file must not stop the batch
            log(f"  ERROR: {e}")
            failures += 1
            continue

        out = b.write(bundle_mod.output_path(path, args.out))
        log(f"  wrote {out}  ({out.stat().st_size / 1024:.0f} KB)")

        if args.tiles:
            try:
                mb = tiles.build(
                    b,
                    bundle_mod.tiles_path(path, args.out),
                    source=args.tile_source,
                    zoom_min=zoom_min,
                    zoom_max=zoom_max,
                    buffer_m=args.tile_buffer,
                    api_key=args.tile_api_key,
                    confirm=not args.yes,
                    log=log,
                )
                if mb:
                    log(f"  wrote {mb}  ({mb.stat().st_size / 1024 / 1024:.1f} MB)")
            except Exception as e:  # noqa: BLE001
                log(f"  ERROR building tiles: {e}")
                failures += 1

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
