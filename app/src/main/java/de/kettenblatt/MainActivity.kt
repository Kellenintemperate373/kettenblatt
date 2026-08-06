package de.kettenblatt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import de.kettenblatt.ui.theme.NaviTheme
import de.kettenblatt.data.GpxExport
import de.kettenblatt.data.Ride
import de.kettenblatt.data.RideStore
import de.kettenblatt.data.Route
import de.kettenblatt.data.RouteMeta
import de.kettenblatt.data.RouteStore
import de.kettenblatt.prep.PrepStage
import de.kettenblatt.prep.RoutePreparer
import de.kettenblatt.prep.TilePack
import de.kettenblatt.prep.TileProgress
import de.kettenblatt.prep.TileSource
import de.kettenblatt.prep.Valhalla
import de.kettenblatt.ui.PrepState
import de.kettenblatt.ui.formatBytes
import de.kettenblatt.data.SettingsStore
import de.kettenblatt.nav.NavigationRepository
import de.kettenblatt.nav.NavigationService
import de.kettenblatt.ui.NavigationScreen
import de.kettenblatt.ui.RideHistoryScreen
import de.kettenblatt.ui.RouteListScreen
import de.kettenblatt.ui.RoutePreviewScreen
import de.kettenblatt.ui.SettingsScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var store: RouteStore
    private var pendingImport by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RouteStore(this)
        pendingImport = intent?.extractRouteUri()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            NaviTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App(store, pendingImport) { pendingImport = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.extractRouteUri()?.let { pendingImport = it }
    }

    /** A route arriving from the share sheet or a file manager. */
    private fun Intent.extractRouteUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}

/** How long an interrupted ride stays offerable as a resume. */
private const val RESUME_WINDOW_MS = 6 * 60 * 60 * 1000L

@Composable
private fun App(store: RouteStore, incoming: Uri?, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    var routes by remember { mutableStateOf(store.list()) }
    var active by remember { mutableStateOf<RouteMeta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val route by NavigationRepository.route.collectAsState()
    val navState by NavigationRepository.state.collectAsState()

    // Reading a route means pulling a whole file through a content provider and
    // parsing it. A cloud-backed URI is a network round-trip and a recorded GPX
    // is tens of thousands of points, so none of it belongs on the main thread.
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val settingsStore = remember { SettingsStore(context) }
    val settings by settingsStore.state.collectAsState()

    // Where the rider is in the app: list -> preview -> navigating.
    var preview by remember { mutableStateOf<RouteMeta?>(null) }
    var previewRoute by remember { mutableStateOf<Route?>(null) }
    var previewReversed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val rideStore = remember { RideStore(File(context.filesDir, "rides")) }
    var rides by remember { mutableStateOf(emptyList<Ride>()) }
    var showHistory by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Ride?>(null) }

    // An unfinished ride means the app died mid-ride. Offer to pick it up while
    // it is still plausibly the same outing; otherwise file it into history.
    var resumable by remember { mutableStateOf<Ride?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val active = rideStore.active()
            val fresh = active != null &&
                System.currentTimeMillis() - active.startedAtMs < RESUME_WINDOW_MS
            if (!fresh) rideStore.finaliseAbandoned(System.currentTimeMillis())
            resumable = if (fresh) active else null
            rides = rideStore.list()
        }
    }

    // Preparing a route: matching against OpenStreetMap, and building an
    // offline map pack. Both were desktop jobs until now.
    var prep by remember { mutableStateOf(PrepState()) }
    var cancelTiles by remember { mutableStateOf(false) }

    fun prepareRoute(meta: RouteMeta, route: Route) {
        prep = PrepState(stage = PrepStage.MATCHING)
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val preparer = RoutePreparer(Valhalla(settings.valhallaUrl)) { stage ->
                        prep = prep.copy(stage = stage)
                    }
                    val prepared = preparer.prepare(route)
                    // Never trade working guidance for none. Matching is
                    // best-effort when a route has no cues yet, but a re-match
                    // that fails -- no signal, a server having a bad day --
                    // must not wipe the 70 turns already on the phone.
                    val keep = prepared.route.hasGuidance || !route.hasGuidance
                    // Preparing rewrites the stored file under a new name, so
                    // the meta the preview is holding is stale from here on --
                    // and a stale one points at a file that no longer exists.
                    val updated = if (keep) store.replaceBundle(meta.id, prepared.bundle) else null
                    Triple(prepared, updated, keep)
                }
            }

            outcome.onSuccess { (prepared, updated, keep) ->
                routes = withContext(Dispatchers.IO) { store.list() }
                updated?.let { preview = it }
                if (keep) previewRoute = prepared.route
                prep = PrepState(
                    warnings = prepared.warnings,
                    done = when {
                        prepared.route.hasGuidance ->
                            "${prepared.route.maneuvers.size} turns" +
                                if (prepared.route.hasReverseGuidance) {
                                    ", ${prepared.route.reverseManeuvers.size} the other way"
                                } else ""
                        keep -> "No usable match; the route still works on geometry alone."
                        else -> "Matching failed, so the cues already on this route were kept."
                    },
                )
            }.onFailure { e ->
                prep = PrepState(error = e.message ?: "Could not prepare this route")
            }
        }
    }

    fun downloadTiles(meta: RouteMeta, route: Route) {
        cancelTiles = false
        prep = PrepState(tiles = TileProgress(0, 0, 0))
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val source = TileSource.byKey(settings.tileSource)
                    val plan = TilePack.plan(
                        points = route.points,
                        source = source,
                        zoomMin = settings.tileZoomMin,
                        zoomMax = settings.tileZoomMax,
                        bufferM = settings.tileBufferM,
                    )
                    prep = prep.copy(tiles = TileProgress(0, plan.tiles.size, 0))

                    val file = store.tilesFileFor(meta.id)
                    TilePack.download(
                        plan = plan,
                        out = file,
                        routeName = route.name,
                        bbox = listOf(
                            route.points.minOf { it.lat }, route.points.minOf { it.lon },
                            route.points.maxOf { it.lat }, route.points.maxOf { it.lon },
                        ),
                        apiKey = settings.thunderforestKey.ifBlank { null },
                        shouldContinue = { !cancelTiles },
                        onProgress = { prep = prep.copy(tiles = it) },
                    )
                    plan to store.attachTilesFile(meta.id)
                }
            }

            outcome.onSuccess { (plan, updated) ->
                routes = withContext(Dispatchers.IO) { store.list() }
                updated?.let { preview = it }
                val done = prep.tiles?.done ?: 0
                prep = PrepState(
                    done = if (cancelTiles) {
                        "Stopped with $done of ${plan.tiles.size} tiles. " +
                            "Starting again picks up from here."
                    } else {
                        "Offline map ready — $done tiles, ${formatBytes(store.tilesFileFor(meta.id).length())}."
                    },
                )
            }.onFailure { e ->
                prep = PrepState(error = e.message ?: "Could not download the map")
            }
        }
    }

    /** Run file work off the main thread, with the spinner up while it lasts. */
    fun withStore(onFailureMessage: String, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { work() }
                .onFailure { error = it.message ?: onFailureMessage }
            routes = withContext(Dispatchers.IO) { store.list() }
            busy = false
        }
    }

    fun importFrom(uri: Uri) = withStore("Unrecognised file") {
        withContext(Dispatchers.IO) { store.import(uri, System.currentTimeMillis()) }
    }

    // A route shared into the app is imported as soon as it arrives.
    LaunchedEffect(incoming) {
        incoming?.let {
            importFrom(it)
            onIncomingHandled()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFrom(it) } }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val ride = exporting
        exporting = null
        if (uri != null && ride != null) {
            withStore("Could not export ride") {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { GpxExport.write(ride, it) }
                }
            }
        }
    }

    // Which route a picked .mbtiles should attach to.
    var attachingTilesTo by remember { mutableStateOf<String?>(null) }
    val tilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val routeId = attachingTilesTo
        attachingTilesTo = null
        if (uri != null && routeId != null) {
            withStore("Could not read tile pack") {
                withContext(Dispatchers.IO) { store.importTiles(routeId, uri) }
            }
        }
    }

    /** Load a route and hand it to the service, off the main thread. */
    fun beginNavigation(meta: RouteMeta, reversed: Boolean) {
        if (busy) return
        busy = true
        active = meta
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.load(meta) } }
                .onSuccess {
                    NavigationRepository.start(meta.id, if (reversed) it.reversed() else it)
                    NavigationService.start(context)
                    preview = null
                    previewRoute = null
                }
                .onFailure {
                    error = it.message ?: "Could not open route"
                    active = null
                }
            busy = false
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val meta = active ?: preview
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true && meta != null) {
            beginNavigation(meta, previewReversed)
        } else {
            error = "Location permission is required to navigate"
            active = null
        }
    }

    // The screen has to stay awake while navigating; that is the whole point of
    // having it mounted on a handlebar.
    KeepScreenOn(enabled = route != null && settings.keepScreenOn)

    val current = route
    if (current != null && active != null) {
        NavigationScreen(
            route = current,
            state = navState,
            offlineTiles = active?.let { store.tilesFile(it) },
            settings = settings,
            onStop = {
                NavigationService.stop(context)
                active = null
            },
            onReverse = { NavigationRepository.replaceRoute(current.reversed()) },
        )
        return
    }

    // Rides are read once at launch, but one gets recorded every time navigation
    // stops -- so without this the ride you have just finished is missing from
    // the list until the app is restarted.
    LaunchedEffect(showHistory) {
        if (showHistory) rides = withContext(Dispatchers.IO) { rideStore.list() }
    }

    if (showHistory) {
        RideHistoryScreen(
            rides = rides,
            units = settings.units,
            onExport = { ride ->
                exporting = ride
                exportPicker.launch(GpxExport.suggestedFileName(ride))
            },
            onDelete = { ride ->
                withStore("Could not delete ride") {
                    withContext(Dispatchers.IO) { rideStore.delete(ride.id) }
                    rides = withContext(Dispatchers.IO) { rideStore.list() }
                }
            },
            onBack = { showHistory = false },
        )
        return
    }

    if (showSettings) {
        SettingsScreen(
            settings = settings,
            onChange = { settingsStore.update(it) },
            onReset = { settingsStore.reset() },
            onBack = { showSettings = false },
        )
        return
    }

    val previewing = preview
    val previewLoaded = previewRoute
    if (previewing != null && previewLoaded != null) {
        RoutePreviewScreen(
            route = previewLoaded,
            reversed = previewReversed,
            offlineTiles = store.tilesFile(previewing),
            units = settings.units,
            prep = prep,
            onSetReversed = { previewReversed = it },
            onPrepare = { prepareRoute(previewing, previewLoaded) },
            onDownloadTiles = { downloadTiles(previewing, previewLoaded) },
            onCancelPrep = { cancelTiles = true },
            onDismissPrepMessage = { prep = PrepState() },
            onStart = {
                // Permissions are asked for here rather than on the list tap, so
                // nothing spins up the GPS until the rider commits to riding.
                val missing = listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                ).filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    beginNavigation(previewing, previewReversed)
                } else {
                    permission.launch(missing.toTypedArray())
                }
            },
            onBack = { preview = null; previewRoute = null; prep = PrepState() },
        )
        // The list screen owns the error dialog, so a failure raised from here
        // -- a route that will not load, most likely -- would otherwise be set
        // and never seen. Show it where it happened.
        error?.let {
            AlertDialog(
                onDismissRequest = { error = null },
                title = { Text("Something went wrong") },
                text = { Text(it) },
                confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
            )
        }
        return
    }

    RouteListScreen(
        routes = routes,
        error = error,
        busy = busy,
        units = settings.units,
        onImport = {
            // Many providers report .gpx and .navi.json as octet-stream, so the
            // filter has to be broad; the extension check happens on import.
            picker.launch(arrayOf("application/gpx+xml", "application/json", "*/*"))
        },
        onOpen = { meta ->
            preview = meta
            previewReversed = false
            withStore("Could not open route") {
                previewRoute = withContext(Dispatchers.IO) { store.load(meta) }
            }
        },
        onOpenSettings = { showSettings = true },
        onOpenHistory = { showHistory = true },
        resumable = resumable,
        onResume = { ride ->
            store.find(ride.routeId)?.let { meta ->
                previewReversed = ride.reversed
                beginNavigation(meta, ride.reversed)
            } ?: run { error = "That route is no longer in the list" }
            resumable = null
        },
        onDiscardResumable = {
            withStore("Could not close the ride") {
                withContext(Dispatchers.IO) { rideStore.finaliseAbandoned(System.currentTimeMillis()) }
                rides = withContext(Dispatchers.IO) { rideStore.list() }
            }
            resumable = null
        },
        onAttachTiles = { meta ->
            attachingTilesTo = meta.id
            tilePicker.launch(arrayOf("application/octet-stream", "*/*"))
        },
        onRename = { meta, name ->
            withStore("Could not rename route") {
                withContext(Dispatchers.IO) { store.rename(meta.id, name) }
            }
        },
        onToggleFavourite = { meta ->
            withStore("Could not update route") {
                withContext(Dispatchers.IO) { store.setFavourite(meta.id, !meta.favourite) }
            }
        },
        onDelete = { meta ->
            withStore("Could not delete route") {
                withContext(Dispatchers.IO) { store.delete(meta.id) }
            }
        },
        onDismissError = { error = null },
    )
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val context = LocalContext.current
    LaunchedEffect(enabled) {
        val window = (context as? ComponentActivity)?.window ?: return@LaunchedEffect
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
