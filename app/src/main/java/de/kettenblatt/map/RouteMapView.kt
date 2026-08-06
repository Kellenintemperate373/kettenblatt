package de.kettenblatt.map

import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.kettenblatt.data.Route
import de.kettenblatt.geo.Geo
import de.kettenblatt.nav.NavState
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.hypot

/** How the map frames the route. Cycles in this order from the map button. */
enum class MapMode {
    /** The whole route at once, north up -- for planning and orientation. */
    OVERVIEW,

    /** Following the rider, course-up, far enough to read the next few streets. */
    NAVIGATION,

    /** Same, but tight in -- for picking the right exit at a busy junction. */
    NAVIGATION_CLOSE;

    val followsRider: Boolean get() = this != OVERVIEW

    fun next(): MapMode = when (this) {
        OVERVIEW -> NAVIGATION
        NAVIGATION -> NAVIGATION_CLOSE
        NAVIGATION_CLOSE -> OVERVIEW
    }
}

/**
 * The osmdroid map, wrapped for Compose.
 *
 * The route is drawn as two polylines split at the rider's position, so the part
 * already covered recedes and the part still to ride stands out -- much easier to
 * read at a glance than one uniform line.
 */
@Composable
fun RouteMapView(
    route: Route,
    state: NavState?,
    mode: MapMode,
    follow: Boolean,
    offlineTiles: File?,
    onUserPan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Zooming past what a sideloaded pack contains does not fall back to the
    // network -- osmdroid upscales the deepest tile it has, and the map turns
    // into blocky mush.
    //
    // The normal mode stays at the pack's own maximum so it is always crisp. The
    // close mode is allowed one level beyond, because a single upscale is still
    // readable and being able to zoom in at a junction is worth more than
    // perfect sharpness. Build packs with --tile-zoom 12-17 to avoid even that.
    val zooms = remember(offlineTiles) {
        val packMax = offlineTiles
            ?.takeIf { it.exists() }
            ?.let { MbtilesMeta.read(it).maxZoom.toDouble() }
        if (packMax == null) {
            NAVIGATION_ZOOM to NAVIGATION_CLOSE_ZOOM
        } else {
            minOf(NAVIGATION_ZOOM, packMax) to minOf(NAVIGATION_CLOSE_ZOOM, packMax + 1)
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            // The zoom buttons overlap the stats panel and duplicate pinch-zoom.
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            setTileSource(TileSources.online())

            // Ground with no tile -- outside a sideloaded pack, or still loading
            // online -- is drawn by osmdroid as a grey cross-hatch, which reads
            // as a broken image rather than as the edge of the map.
            //
            // An offline pack covers the route corridor, and a preview card is
            // wider than a route that runs north to south: the reference route
            // is 5.3 km across in a viewport 11.3 km wide, so nearly 3 km either
            // side is legitimately blank. Filling that with tiles would roughly
            // double the pack for ground nobody rides through, so the honest fix
            // is for blank to look deliberate.
            usePaperForBlankTiles()
        }
    }

    // Overlays are built once per route and then mutated in place.
    //
    // Rebuilding them on every fix meant allocating a GeoPoint per track point
    // twice a second on the main thread. Harmless for a 606-point planned route,
    // but a recorded activity imported as GPX runs to tens of thousands of
    // points, and that much churn at 1 Hz is what makes a map stutter.
    val overlays = remember(route) { RouteOverlays(route) }

    // A sideloaded pack replaces the online provider entirely; a broken one is
    // ignored so the map still works.
    DisposableEffect(offlineTiles) {
        if (offlineTiles != null && offlineTiles.exists()) {
            TileSources.offline(context, offlineTiles)?.let { (provider, source) ->
                mapView.tileProvider.detach()
                mapView.tileProvider = provider
                mapView.setTileSource(source)
                // Swapping the provider rebuilds the tiles overlay, taking the
                // blank-tile colours with it.
                mapView.usePaperForBlankTiles()
            }
        }
        onDispose { }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    ApplyMapMode(
        mapView = mapView,
        route = route,
        mode = mode,
        state = state,
        zoom = if (mode == MapMode.NAVIGATION_CLOSE) zooms.second else zooms.first,
    )

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                // Detach follow-mode on a real finger drag.
                //
                // Deliberately driven from touch rather than osmdroid's scroll
                // events: centring, rotating and offsetting the map all raise
                // scroll events too, so a listener cannot tell the rider's drag
                // from the app's own camera work and follow mode switches itself
                // off on the first fix.
                var downX = 0f
                var downY = 0f
                var dragging = false
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            dragging = false
                        }

                        MotionEvent.ACTION_MOVE ->
                            if (!dragging && hypot(event.x - downX, event.y - downY) > slop) {
                                dragging = true
                                onUserPan()
                            }
                    }
                    false // never consume; the map still handles the gesture
                }

                overlays.attachIfNeeded(this)
                fitRouteWhenReady(route, animated = false)
            }
        },
        update = { map ->
            // Also here, not just in the factory: a new route means new overlays,
            // and the factory does not run again for an existing MapView.
            overlays.attachIfNeeded(map)
            overlays.apply(map, state)

            state?.let {
                if (mode.followsRider && follow) {
                    overlays.centreOn(map, it)
                    it.routeBearingDeg?.let { bearing -> map.pointUp(bearing) }
                }
            }

            map.invalidate()
        },
    )
}

/**
 * The map overlays for one route, created once and updated in place.
 *
 * Only two things actually change while riding -- where the line splits between
 * covered and remaining, and where the rider is -- so only those are touched.
 */
private class RouteOverlays(private val route: Route) {

    /** Built once; the polylines below take sublists of this. */
    private val geoPoints: List<GeoPoint> = route.points.map { GeoPoint(it.lat, it.lon) }

    private val remaining = remainingLine()

    /**
     * One polyline per covered stretch.
     *
     * Coverage is not a single prefix of the route: skip a section, or detour
     * off and rejoin, and what you rode is several disjoint runs. Drawing them
     * separately is what lets a missed stretch stay bright.
     */
    private val travelled = ArrayList<Polyline>()
    private var position: Marker? = null

    private var appliedCoverage = -1
    private var lastCentre: GeoPoint? = null
    private var attachedTo: MapView? = null

    fun attachIfNeeded(map: MapView) {
        if (attachedTo === map) return
        attachedTo = map

        map.overlays.clear()
        map.overlays.add(remaining)

        route.waypoints.forEach { wp ->
            map.overlays.add(waypointMarker(map, GeoPoint(wp.lat, wp.lon), wp.name ?: wp.sym))
        }

        // Which way round the route goes. Two dots at the ends cannot say that
        // on a loop, where they sit almost on top of each other.
        DirectionArrows.along(route).forEach { arrow ->
            map.overlays.add(directionMarker(map, geoPoints[arrow.index], arrow.bearingDeg))
        }

        map.overlays.add(endpointMarker(map, geoPoints.first(), "Start", start = true))
        map.overlays.add(endpointMarker(map, geoPoints.last(), "Finish", start = false))

        position = positionMarker(map, geoPoints.first()).also {
            // Before the first fix the chevron stands at the start; pointing it
            // north there would contradict the arrows it sits among.
            DirectionArrows.bearingAt(route, 0)?.let { b -> it.rotation = -b.toFloat() }
            map.overlays.add(it)
        }

        travelled.clear()
        appliedCoverage = -1
        remaining.setPoints(geoPoints)
    }

    fun apply(map: MapView, state: NavState?) {
        val coveredCount = state?.covered?.coveredCount ?: 0
        if (coveredCount != appliedCoverage) {
            appliedCoverage = coveredCount
            applyCoverage(map, state?.covered)
        }
        state?.let { s ->
            position?.apply {
                position = geoPoints[s.snappedIndex]
                s.routeBearingDeg?.let { rotation = -it.toFloat() }
            }
        }
    }

    private fun applyCoverage(map: MapView, covered: de.kettenblatt.nav.CoveredSegments?) {
        val runs = covered?.runs().orEmpty()

        // Reuse the polylines already on the map; only add or drop when the
        // number of disjoint covered stretches actually changes.
        while (travelled.size < runs.size) {
            travelledLine().also {
                travelled.add(it)
                // Above the route line, below the markers: the remaining line
                // spans the whole route, so anything underneath it is invisible.
                map.overlays.add(map.overlays.indexOf(remaining) + 1, it)
            }
        }
        while (travelled.size > runs.size) {
            map.overlays.remove(travelled.removeAt(travelled.lastIndex))
        }

        runs.forEachIndexed { i, run ->
            // Sublists share the backing list, so no coordinates are re-allocated.
            val from = run.first.coerceIn(0, geoPoints.lastIndex)
            val to = (run.last + 1).coerceIn(from + 1, geoPoints.size)
            travelled[i].setPoints(geoPoints.subList(from, to))
        }
    }

    /**
     * Recentre, but only once the rider has actually moved.
     *
     * Restarting the animation on every fix while stationary leaves the map
     * permanently mid-animation and visibly twitching.
     */
    fun centreOn(map: MapView, state: NavState) {
        val target = geoPoints[state.snappedIndex]
        val previous = lastCentre
        if (previous != null &&
            Geo.haversine(previous.latitude, previous.longitude, target.latitude, target.longitude)
            < RECENTRE_THRESHOLD_M
        ) {
            return
        }
        lastCentre = target
        map.controller.animateTo(target)
    }
}

private const val RECENTRE_THRESHOLD_M = 3.0

/**
 * The tone of the map's own paper, for ground with no tile.
 *
 * Deliberately keyed to the tiles rather than to the app theme: the raster map
 * is light in both themes, so a dark fill here would read as a hole punched in
 * the map rather than as its edge. Sampled from OpenTopoMap's own background.
 */
private const val MAP_PAPER = 0xFFF2F1EE.toInt()

/**
 * Draw ground with no tile as plain map paper rather than a cross-hatch.
 *
 * osmdroid's placeholder is a grey hatch, which reads as a broken image. Blank
 * is legitimate here -- a preview card is wider than a north-south route, and an
 * offline pack only covers the corridor -- so it should look like the edge of the
 * map, not like a failure.
 */
private fun MapView.usePaperForBlankTiles() {
    overlayManager.tilesOverlay.apply {
        setLoadingBackgroundColor(MAP_PAPER)
        setLoadingLineColor(MAP_PAPER)
    }
    setBackgroundColor(MAP_PAPER)
}

/**
 * Apply a mode change once, rather than on every recomposition.
 *
 * Keyed on whether a fix exists as well as the mode: navigation mode has nothing
 * to centre on until the first fix arrives, so until then it shows the overview.
 */
@Composable
private fun ApplyMapMode(
    mapView: MapView,
    route: Route,
    mode: MapMode,
    state: NavState?,
    zoom: Double,
) {
    val hasFix = state != null
    LaunchedEffect(mode, hasFix, zoom) {
        val here = state?.let { route.points[it.snappedIndex] }
        if (!mode.followsRider || here == null) {
            mapView.setMapOrientation(0f)
            mapView.setMapCenterOffset(0, 0)
            // Animate when the rider asked for the overview, but cut straight
            // there when there is no fix yet. That second case includes swapping
            // the route on a reverse, where animating means sliding across the
            // country from a viewport that no longer means anything.
            mapView.fitRouteWhenReady(route, animated = here != null)
        } else {
            // Sit the rider low on the screen so most of the map shows the road
            // ahead rather than the road already ridden. A positive offset moves
            // the centred point down the screen.
            mapView.setMapCenterOffset(0, (mapView.height * POSITION_DROP).toInt())
            mapView.controller.setZoom(zoom)
            mapView.controller.animateTo(GeoPoint(here.lat, here.lon))
            state.routeBearingDeg?.let { mapView.pointUp(it) }
        }
    }
}

/**
 * Turn the map so `bearing` points up the screen.
 *
 * The deadband matters: without it every fix nudges the rotation by a fraction
 * of a degree and the map shimmers continuously.
 */
private fun MapView.pointUp(bearing: Double) {
    val desired = -bearing.toFloat()
    if (Geo.bearingDelta(mapOrientation.toDouble(), desired.toDouble()) > ROTATION_DEADBAND_DEG) {
        setMapOrientation(desired)
    }
}

/**
 * Fit the whole route, waiting for layout first.
 *
 * `zoomToBoundingBox` does not fail on a zero-width view -- it spins inside
 * Projection.getCloserPixel and hangs the main thread, which reads as the app
 * freezing and takes the navigation service down with it.
 */
private fun MapView.fitRouteWhenReady(route: Route, animated: Boolean) {
    if (width > 0 && height > 0) {
        zoomToBoundingBox(route.boundingBox(), animated, MAP_PADDING_PX)
    } else {
        addOnFirstLayoutListener { _, _, _, _, _ ->
            zoomToBoundingBox(route.boundingBox(), false, MAP_PADDING_PX)
        }
    }
}

private const val MAP_PADDING_PX = 80

/** Close enough to read street layout at cycling speed, wide enough to see ahead. */
private const val NAVIGATION_ZOOM = 16.0

/** Junction detail: roughly a 400 m-wide view. */
private const val NAVIGATION_CLOSE_ZOOM = 18.0

/** Fraction of the screen height the rider sits below centre in navigation mode. */
private const val POSITION_DROP = 0.22

private const val ROTATION_DEADBAND_DEG = 3.0

private fun Route.boundingBox(): BoundingBox {
    val lats = points.map { it.lat }
    val lons = points.map { it.lon }
    return BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
}

/**
 * The part already ridden: a solid slate, thinner than the road ahead.
 *
 * It was previously drawn at 43% alpha, which over a busy raster map composited
 * to something almost indistinguishable from the map's own greys -- so the split
 * that is supposed to show progress showed nothing. Contrast comes from being a
 * different colour and weight, not from being faint.
 */
private fun travelledLine() = Polyline().apply {
    outlinePaint.apply {
        color = Color.argb(225, 122, 133, 150)
        // Matches the route line rather than sitting inside it; a narrower line
        // would leave a bright blue fringe along every ridden stretch.
        strokeWidth = 13f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

private fun remainingLine() = Polyline().apply {
    outlinePaint.apply {
        color = Color.argb(255, 29, 111, 242)
        strokeWidth = 13f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

private fun endpointMarker(map: MapView, at: GeoPoint, label: String, start: Boolean) =
    Marker(map).apply {
        position = at
        title = label
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = if (start) Markers.start(map.context) else Markers.finish(map.context)
    }

private fun waypointMarker(map: MapView, at: GeoPoint, label: String?) = Marker(map).apply {
    position = at
    title = label
    // Anchored at the tip, because a pin points at the place it marks.
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    icon = Markers.waypoint(map.context)
}

private fun directionMarker(map: MapView, at: GeoPoint, bearingDeg: Double) = Marker(map).apply {
    position = at
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    icon = Markers.directionArrow(map.context)
    rotation = -bearingDeg.toFloat()
    isFlat = true
    // Context, not a target: tapping one should do nothing at all.
    setOnMarkerClickListener { _, _ -> true }
    setInfoWindow(null)
}

private fun positionMarker(map: MapView, at: GeoPoint) = Marker(map).apply {
    position = at
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    icon = Markers.position(map.context)
    // Flat means the chevron turns with the map. Combined with course-up that
    // cancels out to "always pointing up the screen", and in north-up overview
    // it points along the direction of travel. Both are what you want.
    isFlat = true
}
