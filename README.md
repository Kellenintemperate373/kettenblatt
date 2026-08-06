# Kettenblatt

Follow GPX routes on Android, offline.

*Kettenblatt* is German for the chainring — the part that turns everything else.

Plan a trip in any planner that exports GPX — Komoot, RideWithGPS, Strava,
Garmin Connect — then open the file on your phone. Kettenblatt matches it
against OpenStreetMap data downloaded over wifi, so navigation works with no
signal and no account.

In the field it shows the line, where you are on it, and the next turn with its
street name — and buzzes if you leave the route. It records the ride as you go,
so an interrupted one can be picked up again and a finished one exported as GPX.
**While riding: no network connection, and no sound.**

---

Kettenblatt is an independent open-source project. It is not affiliated with,
endorsed by, or connected to komoot GmbH, Strava Inc., or any other route
planner. Komoot is a trademark of komoot GmbH.

---

```
  PHONE (at home, on wifi)                     PHONE (in the field, offline)
  ────────────────────────                     ────────────────────────────
  route .gpx ─► import
      │
      ├─► Add turn cues ──► valhalla1.openstreetmap.de
      │                     (trace_route + trace_attributes)
      │                            │
      │   ┌────────────────────────┘
      │   ▼
      ├─► 70 turns, 67 the other way, 82 surface spans  ──►  navigate,
      └─► Offline map ──► 431 tiles, 9 MB  ─────────────►    entirely offline
```

Everything above happens on the phone. There is no desktop step, no Docker, and
nothing to install but the app.

## Why map matching exists

Komoot's GPX export contains **only geometry** — trackpoints with lat/lon and
elevation. No turn instructions; their docs are explicit that voice navigation
is not included.

Deriving turns from geometry alone does not work. On the reference route
(28.8 km near Venlo), **119 vertices turn by more than 25°** — an alert every
240 m — because bearing change cannot distinguish a bend in the road from a
junction.

Map-matching the track against OpenStreetMap fixes that properly. The same route
yields **70 real turns, each at an actual junction, 81% with a street name**,
plus surface type, and both Maas ferry crossings identified as such.

The original GPX geometry stays authoritative for navigation. Map matching
only *annotates* it, so a poor match costs street names — never the line you are
following.

That needs an OSM routing graph, which is the one thing an app cannot carry: a
region's Valhalla tiles are hundreds of megabytes and have to be built on a
desktop anyway. So the phone asks a Valhalla server instead — once per route, at
home, over wifi. Nothing about the ride itself touches the network.

## Preparing a route on the phone

Import a `.gpx` and the preview offers **Add turn cues**. That sends the track to
Valhalla, maps the returned maneuvers back onto the original geometry, matches the
reverse direction too, and rewrites the route in place. On the reference route it
takes about a second and yields the same 70 turns, 67 reverse cues and 82 surface
spans that the Python pipeline this was ported from produced — verified by
diffing the two files before that pipeline was retired.

![Preparing a route on the phone](docs/screens/prepare-on-phone.png)

**Offline map** does the other half: the tile corridor, downloaded straight into an
MBTiles pack in the route's own storage. 431 tiles and 9 MB for the reference
route, in well under a minute. Stopping is safe and starting again resumes, so an
interrupted download costs only the tiles in flight.

Both are best-effort in the way that matters: if the server cannot be reached the
route still imports and navigates on geometry alone, with the reason shown. And a
re-match that fails **never replaces cues you already have** — losing 70 working
turns to a moment of bad signal would be strictly worse than doing nothing.

Which server it asks is a setting. The default is
[FOSSGIS's public instance](https://valhalla.openstreetmap.de) — whole planet, no
API key, tileset refreshed daily, fair-use limit of one call a second against the
four a route costs. If you would rather not lean on a shared instance, run your
own with [the project's Docker image](https://github.com/nilsnolde/docker-valhalla)
and a [Geofabrik extract](https://download.geofabrik.de/) for your region, then
put its address in Settings. Kettenblatt speaks the standard `trace_route` and
`trace_attributes` API, so any Valhalla will do — Stadia Maps included.

### Why both directions are matched, not flipped

A round trip is the same line ridden either way, so direction is the only thing
that distinguishes the two rides — and it must not cost you guidance to choose.

Cues cannot be mirrored after the fact. A maneuver says *"turn right onto
Kaldenkerkerweg"*; ridden the other way you meet that junction from a different
arm, turn the other way, and join a different street. Flipping left for right
would produce confident, wrong directions. So the reversed geometry is
map-matched separately and both cue sets travel in the same bundle — 70 forward
and 67 backward on the reference route. Reversing mid-ride swaps them. This is
why preparing a route costs four requests rather than two.

## Managing routes

Each card has an overflow menu: **favourite**, **rename**, **replace offline
map** (for a pack built elsewhere), **delete**. Favourites pin to the top of the
list, then everything else by most recently imported.

Renaming changes only the display name — the stored file keeps its own, so a
route never stops opening because you tidied its title. Deleting removes the
tile pack along with the bundle, and says so before you confirm.

## Before you set off

Tapping a route opens a **preview** rather than starting navigation: the map, the
elevation profile, distance, ascent and turn count, the surface split, the
waypoints with how far along each one falls, and the direction selector. GPS and
the foreground service only start when you press **Start ride** — opening a route
to look at it should not cost battery.

Direction is chosen here, before setting off, because that is when you know which
way round you are riding. Reversing mid-ride is still available from the ride
menu.

![Route preview](docs/screens/route-preview.png)

## While riding

- **No sounds at all.** Alerts are vibration only: a tone competes with traffic
  and with anyone nearby, and it is the one signal you cannot turn down without
  silencing the phone. Four distinguishable patterns — two long pulses for off
  route, three short for wrong direction, one short on rejoining, and two quick
  taps for an approaching waypoint.
- **Stopping asks first.** A stray tap on a bar-mounted phone should not end the
  ride, so the stop button confirms and shows how far is left.
- **Arriving stops by itself** a minute after the finish, rather than holding the
  GPS for the rest of the evening.
- **Screen off drops GPS from 1 s to 4 s** between fixes. Accuracy is deliberately
  *not* reduced: the tracker discards fixes worse than 30 m, so a balanced-power
  provider would silently stop navigation in your pocket. The cost is that
  off-route takes about twelve seconds to notice instead of three.

### Auto screen dim

Between turns there is nothing on screen worth looking at, and the panel is the
biggest power draw on a bar-mounted phone. After 12 s with no maneuver within
300 m the map blacks out and the backlight drops to minimum; an approaching turn,
going off route, arriving, or a tap brings it straight back. Toggle it from the
ride menu.

An app cannot switch the panel off — that needs device-admin rights, and waking
it again is unreliable across manufacturers. This draws full black at minimum
brightness instead, which on the Pixel's OLED means nearly every pixel is off.
The advantage over letting the system time out is that waking is instant and
entirely under the app's control, so a turn can bring the screen back with no
keyguard in the way.

It stays disabled on routes with **no turn cues**, since nothing could ever wake
it and the map is then the only thing telling you where to go.

### Direction and progress

The ride menu reverses direction mid-ride, keeping full guidance from the cue set
matched for that direction. Ascent is recomputed rather than negated — the climbs
one way are the descents of the other.

The dimmed line shows what you have **genuinely ridden**, recorded segment by
segment, not simply everything behind your current position. Skip a section, or
bypass one during an off-route detour, and it stays bright so you can see what
you missed.

![Ridden stretches dim, a skipped one stays bright](docs/screens/coverage-skipped-stretch.png)

### Waypoints and surfaces

A waypoint you deliberately routed past — the ice cream place, the ferry, the
lunch stop — announces itself 600 m out as a chip under the turn banner, with one
short double buzz at 200 m. It sits *below* the turn rather than replacing it: a
junction always outranks a café.

![Waypoint chip](docs/screens/waypoint-chip.png)

The same slot warns about **surface changes**, from the spans matching returns
alongside the cues — *"Compacted — 290 m to go"* — with enough room to pick a
line or change gear. The preview shows the whole split, and marks ferries.

### Recording, history and export

Every ride is recorded while you navigate: the trail of accepted fixes plus which
route segments were actually covered. Fixes accumulate in memory and are flushed
every 30 s, so a crash costs half a minute rather than the ride.

Finished rides appear under the history icon with distance ridden, moving time,
ascent, average speed and the covered percentage — coverage being the honest
measure of whether you followed the route, which distance alone will not tell
you. Each can be **exported as GPX** (a `<trk>` of timestamped points, which
Komoot, Strava and Garmin all read) or deleted.

Moving time excludes stops, and both distance and average exclude legs the rider
could not plausibly have ridden — a fix that lands a kilometre away in three
seconds is a bad fix, and counting it once turned a 7.7 km outing into a
495 km/h average.

### Picking a ride back up

If the app dies mid-ride — a crash, a system kill, a flat battery on the charger
— the unfinished ride is offered on the route list next time you open it, for six
hours. **Resume** restarts navigation on the same route and direction, restores
what you had already covered so the dimmed line survives, and keeps appending to
the same trail. **Finish it** files it into history instead.

## Settings

The gear in the route list covers units (kilometres or miles, applied everywhere
including speed and ascent), the two off-route thresholds, keep-screen-on, the
auto-dim delay and wake distance, the two navigation zoom levels, the Valhalla
server used for matching, and the tile source, deepest zoom and corridor width
used for offline packs. Every value
has a sane default, and *Reset* returns to them.

The off-route sliders refuse an inverted pair — clearing further out than the
alert triggers would make the alert flap continuously, so the setting cannot be
saved that way in the first place.

## Look and feel

Material 3 with a deliberately narrow palette: one accent — the same blue the
route is drawn in — and otherwise neutral surfaces, because the map already
carries plenty of colour. **Light and dark follow the system setting**, and the
dark theme is a true dark rather than an inversion; the panels are near-black but
not OLED-black, which is harsh next to a bright raster map at night.

**Markers are drawn in code** (`map/Markers.kt`) rather than shipped as assets,
so they share one visual system: a solid shape, a white keyline, a soft shadow.
That keyline is what keeps them legible over both the pale greens of OpenTopoMap
and the dense grey of a town centre.

| Marker | |
|---|---|
| Position | Blue chevron, larger than the rest — the one thing you look for while moving. It rotates to the direction of travel, so it points up the screen in course-up mode and along the route in overview. Before the first fix it stands at the start, already pointing the way the route goes. |
| Direction | Small white arrowheads spaced along the line itself. |
| Start / Finish | Small green and near-black dots. Endpoints should be findable, not shouty. |
| Waypoint | Amber pin, anchored at its tip because a pin points at the place it marks. |

**Ground with no tile is drawn as blank map paper, not as osmdroid's grey
cross-hatch**, which reads as a broken image. Some blank is unavoidable: an
offline pack covers the route corridor, while the preview card is landscape and
a route often is not. The reference route is 5.3 km across in a card 11.3 km
wide, so nearly 3 km either side is legitimately empty — covering it would take
the pack from 431 tiles to about 900, and 9 MB to 19 MB, for ground nobody rides
through. Cheaper and more honest to make empty look deliberate. The colour is
keyed to the tiles rather than the theme, because the raster map is light in dark
mode too, and a dark fill would read as a hole punched in the map.

**Arrowheads along the line say which way round you ride.** Two dots at the ends
cannot: on a loop they sit within a few metres of each other, so the map looked
identical whichever direction was selected, and choosing *Reverse* changed
nothing you could see. Roughly one arrow per 2.5 km, clamped to between six and
twenty so a short route still reads and a long one does not turn into a dotted
line.

Each one slides up to 300 m from its ideal spacing to find the straightest point
nearby. An arrow that lands mid-bend points along the road correctly but not
along the pixels either side of it, which reads as a mistake — and the spacing
was arbitrary to begin with, so there is nothing to lose by moving it. Curvature
is measured by comparing the bearing arriving with the bearing leaving: looking
only ahead scores the *exit* of a bend as perfectly straight, which is exactly
where an arrow looks wrong.

**The elevation profile toggles** from the terrain button on the right of the map,
so you can trade it for more map on a flat route. It carries its own range label
("12–44 m") and a dot showing where you are on the climb. The button fills with
the accent colour while the profile is up, so its state is readable at a glance,
and routes with no ascent never show the control at all.

![Elevation profile shown, then toggled off](docs/screens/elevation-toggle.png)

Two things worth knowing, both found by looking at the result rather than the code:

- The **travelled part of the route** used to be drawn at 43% alpha, which over a
  busy raster map composited to something indistinguishable from the map's own
  greys — the split that exists to show progress showed nothing. It is now a solid
  slate at the same width as the road ahead. Contrast comes from being a different
  colour, not from being faint or thin.
- M3's **tonal elevation** tints a surface toward the primary colour. On cards and
  panels this size it reads as "slightly blue" rather than as depth, so they use
  shadow only.

## The two map modes

The button on the right of the map cycles through three, showing the icon for
what the *next* tap gives.

| | **Navigation** | **Close** | **Overview** |
|---|---|---|---|
| Framing | street level | junction detail | whole route |
| Orientation | course-up | course-up | north up |
| Position | low on screen, so most of the map is road ahead | same | wherever it falls |
| Follows you | yes | yes | no |

Navigation is the default. Dragging the map by hand parks it and a recentre
button appears; recentring or switching mode resumes following. Only real finger
drags count — osmdroid raises scroll events for the app's own centring and
rotation too, so follow mode is driven from touch instead.

Close mode is allowed **one zoom level beyond** an offline pack's maximum: a
single upscale is still readable, and being able to zoom in at a junction is
worth more than perfect sharpness. Raise *Deepest zoom* in Settings before
downloading to avoid even that.

Course-up uses the bearing of the route **60 m ahead** rather than the current
segment or GPS course. Segments average 29 m on the reference route, so a
per-segment bearing makes the map judder, and GPS course is meaningless at
walking pace.

**One consequence of raster tiles:** rotating the map rotates the pre-rendered
street labels with it, so heading south they read upside-down. Nothing can fix
that short of vector tiles. If you would rather have permanently readable labels
and orient yourself mentally, drop the `pointUp` call in `RouteMapView.kt` and
navigation mode becomes zoomed-in north-up.

## Building it

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Kettenblatt appears in the share sheet and as a handler for `.gpx`, `.navi.json`
and `.mbtiles`, so a route or a map pack can also be shared in from elsewhere.

## Releases

Tagging is the whole process:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

That runs the tests, builds a signed APK, and publishes a GitHub Release with
generated notes. `versionName` comes from the tag and `versionCode` is derived
from it (`1.2.3` becomes `10203`), so rebuilding a tag produces the same numbers
and upgrade ordering falls out of semver rather than a counter.

**Releases are shrunk with R8, and that is not optional.** `material-icons-extended`
compiles some five thousand icons into code and the app uses 28 of them: the
unshrunk APK is 46 MB, the shrunk one 3.5 MB. `app/proguard-rules.pro` keeps
osmdroid whole, since it names tile sources by class and reads configuration
reflectively, and keeps the generated `kotlinx.serialization` serializers, which
are looked up by name. Anything else stripped in error surfaces only at runtime,
so a release build is worth installing and exercising before tagging — import,
prepare, ride, stop, history.

### One-time signing setup

Android ties app identity to the signing key, so a build signed with a different
key cannot upgrade an installed one — users would have to uninstall and lose
their routes and rides. Create the key once, keep it safe, and never rotate it.

```sh
keytool -genkeypair -v -keystore kettenblatt.jks -alias kettenblatt \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` asks for **one** password. Two secrets exist because a keystore is a
container that can hold several keys, and the older JKS format let each key carry
its own password; Gradle's API still separates them. The modern default format is
PKCS12, which has no per-key password at all — pass a different one and keytool
answers *"Different store and key passwords not supported for PKCS12 KeyStores.
Ignoring user-specified -keypass value."* So `KEYSTORE_PASSWORD` and
`KEY_PASSWORD` are the same string, the one you typed. If keytool offers a key
password prompt, press RETURN to reuse the keystore password.

Then store it for CI, base64-encoded because a secret has to be text:

```sh
gh secret set KEYSTORE_BASE64  < <(base64 -i kettenblatt.jks)
gh secret set KEYSTORE_PASSWORD   # the password keytool asked for
gh secret set KEY_PASSWORD        # the same one again
gh secret set KEY_ALIAS           # kettenblatt
```

Keep `kettenblatt.jks` somewhere durable and out of the repo — losing it means
never being able to update the app for anyone who installed it.

To build a signed APK locally, put the same four values in `keystore.properties`
at the repo root (gitignored):

```properties
storeFile=/absolute/path/to/kettenblatt.jks
storePassword=…
keyAlias=kettenblatt
keyPassword=…
```

Without it, `./gradlew :app:assembleRelease` still works and simply produces an
unsigned APK, so a clone can be built by anyone.

## Tests

```sh
./gradlew :app:testDebugUnitTest    # 173 tests
```

They run against the real reference route rather than synthetic data.

**Preparation is pinned to recorded responses.**
`app/src/test/resources/venlo_trace_{route,attributes}.json.gz` are real Valhalla
replies; `venlo_expected.json` is the cue and span output verified against the
Python implementation this code was ported from. Feeding the port those exact
bytes and asserting it still reproduces all 70 cues at the same indices with the
same street names, all 80 spans and the same match quality means tileset drift on
the live service can never be mistaken for a regression, and a divergence names
the maneuver that broke. Tile selection is pinned the same way, against the
corridor and bbox counts at each zoom.

The GPX export is tested by importing it straight back through the app's own
`GpxImport` — which needs a real `XmlPullParser`, since `android.util.Xml` is a
stub off-device. `GpxImport.parse` takes one as an optional argument for exactly
that reason, and the tests pass kxml2.

The whole flow has also been exercised on an emulator: importing a raw `.gpx`,
preparing it **on the phone**, riding it with simulated GPS (`adb emu geo
fix` fed from the route's own points), watching the turn banner advance through
real street names, drifting off route to raise the alert, approaching a waypoint
and an unpaved span for their chips, killing the app mid-ride and resuming it,
exporting a ride and importing the result back as a route, downloading an offline
pack and rendering from it in **airplane mode**, and preparing a 7,209-point
recording to exercise trace thinning.

The strongest check: the bundle the phone produced was pulled off the device and
diffed against the one the Python pipeline wrote from the same GPX. Identical
geometry,
identical 70 forward and 67 reverse cues down to each instruction string,
identical 82 surface spans, identical match quality.

Give the emulator room: on a 2 GB AVD deep into swap, the first frame of the
preview map took long enough to ANR. The same build on a 4 GB cold boot with
`-gpu host` opens it instantly.

Android Studio's Extended Controls → Location → Routes can also import a GPX and
play it back as simulated GPS, which is the easier way to do this by hand.

## Things that will bite you

Each of these cost real debugging time and is pinned by a test or a comment.

**`search_radius` must be 100.** Komoot decimates its routes — the reference file
has gaps up to 517 m. Below ~75 m the matcher cannot find candidate edges across
those gaps, so the router bridges them by picking its own cheaper path and
returns a route **15% shorter than the input with no error raised**. 100 is also
Valhalla's maximum, so there is no headroom; `assess_match` exists to catch the
failure if a sparser route ever hits it.

**MBTiles rows are TMS-ordered.** `tile_row = 2^z − 1 − y`, flipped relative to
slippy-map XYZ. Get it wrong and you get a file full of perfectly good tiles that
renders as a blank map.

**osmdroid needs a real user agent.** `Configuration.getInstance().userAgentValue`
must be set, or every tile silently fails and the map is grey with nothing in the
log. Mapnik's tile policy declares `FLAG_USER_AGENT_MEANINGFUL` and the library's
default is rejected.

**`zoomToBoundingBox` before layout hangs the app.** Called while the `MapView`
still has zero width it does not throw — it spins inside
`Projection.getCloserPixel` on the main thread until the system ANRs the process.
It has to go inside `addOnFirstLayoutListener`. This one presented as "the app
freezes and the navigation service dies 30 seconds later", because the blocked
main thread also prevented `startForeground()` from ever running.

**The `VIBRATE` permission is not optional.** Without it the first off-route
alert of a ride throws `SecurityException` from the location callback and kills
the app — in the field, at exactly the moment you need it. The alert path now
treats audio and haptics as best-effort for the same reason.

**osmdroid will not bulk-download the default map source.** Mapnik carries
`FLAG_NO_BULK`, so constructing a `CacheManager` against it throws — the library
enforcing the OSM Foundation's tile policy, and rightly so. `prep/TilePack.kt`
therefore does not use `CacheManager` at all: it fetches from a source that
permits caching and writes the MBTiles itself.

**MBTiles carries no tile-source name**, so `IArchiveFile.getTileSources()` returns
an empty set and the name has to be supplied in code.

**Zooming past a tile pack's maximum does not fall back to the network.** osmdroid
upscales the deepest tile it has and the map turns to mush, so navigation zoom is
clamped to the pack's own `maxzoom`. Packs default to zoom 12–16; raise *Deepest
zoom* in Settings before downloading if you want to navigate closer in offline.

**Out-and-back spurs are genuinely ambiguous.** The reference route detours to a
waypoint and retraces *identical coordinates* home — indices 223 and 225 are the
same point. Nothing about a single fix says which leg you are on; the tracker
resolves it by continuity with the previous fix, preferring forward progress.

**Tie-breaking must prune against the running best, not the previous one.** The
snapping scan collects candidates within 5 m of the best cross-track distance.
Clearing that list only when a *single step* improves by more than the tolerance
looks equivalent — but where cross-track distance falls gradually, which is the
normal case on a densely sampled recorded track, no step ever clears it and
candidates kilometres away survive to win the continuity tie-break. A 15k-point
track then reads as permanently off route. Prune on every improvement.

**A GPX `<link>` has a `<type>` too, and it is a MIME type.** Komoot's
`<metadata><link>` block carries `<type>text/html</type>` *before* the track's own
`<type>e_touring_bicycle</type>`, so taking the first `<type>` anywhere read every
export as "text/html". Harmless-looking until it matters twice over: the activity
picks the costing model, so a hike would have been matched with bicycle costing,
and it picks the ETA default, so a walk was estimated at 16 km/h. Scope the tag to
`<trk>`.

**Never trade working guidance for none.** Matching is best-effort — a route with
no cues is still worth importing on geometry alone. But applying that same rule to
a *re-match* means one tap with no signal replaces 70 working turns with nothing,
which is strictly worse than doing nothing at all. The new bundle is only stored
when it has cues, or when there were none to lose.

**`matched_points` is 1:1 with what you sent, not with your track.** A long
recording has to be thinned before matching — 7,000 points at 1 Hz will be refused
— and the surface spans that come back are then indexed against the *thinned*
trace. Without a decimated→original index map they all land in the first fifth of
the route, which looks plausible enough to ship. Cue mapping is immune, because it
locates maneuvers geographically rather than by index.

**A provider that reports a speed can still be lying.** The tracker used to
prefer `Location.getSpeed()` whenever it was present and only fall back to
deriving speed from progress when it was absent. The emulator's fused provider
reports **0.18 m/s at any pace**, and some handsets do the same — below the
"moving" threshold, so the speed window never filled, Speed showed "—" for the
whole ride, and ETA sat on the 16 km/h activity default forever. Either signal
now counts as movement. The derived one is measured over five seconds rather than
fix-to-fix, or a parked rider's GPS jitter reads as walking pace.

**Progress jumps are not sprints.** Coming out of a tunnel or off a cold start,
a fix can land hundreds of metres along the route. Averaged into a five-minute
speed window it produced a 180 km/h readout and an ETA in the past; carried into
the ride summary it produced a 495 km/h average. Anything implying more than
25 m/s is discarded — in the live window and in the stored ride alike.

**Coverage fill has to be bounded by distance, not by point count.** The gap
between two consecutive fixes is bridged so the ridden line is continuous rather
than striped. Bounding that at 40 *points* means 80 m on a dense recorded track
and nearly 2 km on a decimated Komoot route — where it quietly marked a genuine
2 km shortcut as ridden. The bound is 150 m of route.

**Overlay order is not a detail.** The remaining-route polyline spans the whole
route and is never trimmed; the ridden stretches are drawn on top of it. Inserted
*below* — which is what `overlays.indexOf(remaining)` gives you — they are painted
and then immediately covered, so coverage tracking works perfectly and shows
nothing at all.

**Ascent needs both a threshold and a smoothing window.** They fix different
things: the threshold handles clean terrain-model elevation (68 m → 44 m on the
reference route), the 60 m window handles noisy recorded tracks (450 m → 99 m on
a synthetic climb). Neither alone is enough.

## Layout

```
app/src/main/java/de/kettenblatt/
  data/                bundle + gpx parsing, route storage, rides, settings, gpx export
  geo/                 haversine, bearings, local-plane projection
  prep/                valhalla client, maneuvers, pipeline, bundle writer, tiles
  nav/                 RouteTracker (pure Kotlin), foreground service, recorder, alerts
  map/                 osmdroid wrapper, tile sources
  ui/                  Compose screens (list, preview, navigation, rides, settings)
docs/screens/          screenshots used by this README
```

`prep/` is a port of a Python pipeline that used to run on a desktop, and it
keeps that origin's habit of writing down *why* each constant is what it is —
`search_radius`, the maneuver types that are deliberately ignored, the length
deviation that makes a match unusable. Those numbers were expensive to find.

Anything worth testing lives in a class with no Android imports —
`RouteTracker`, `Ride`, `RideStore`, `SettingsCodec`, `RouteIndex`, `GpxExport` —
so the suite runs on the JVM in seconds rather than needing a device.

## Licence

[Apache 2.0](LICENSE). The maps are © OpenStreetMap contributors; OpenTopoMap's
styling is CC-BY-SA, and its tile servers are volunteer-run, so keep offline
packs to the corridors you actually ride.
