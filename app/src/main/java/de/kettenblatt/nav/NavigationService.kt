package de.kettenblatt.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.kettenblatt.MainActivity
import de.kettenblatt.R
import de.kettenblatt.data.Ride
import de.kettenblatt.data.RideStore
import de.kettenblatt.data.Route
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import de.kettenblatt.ui.formatDistance
import de.kettenblatt.ui.formatDuration

/**
 * Drives navigation while the app is in use.
 *
 * Started while the activity is visible, which is what lets it use
 * `foregroundServiceType="location"` without also requesting
 * ACCESS_BACKGROUND_LOCATION -- one fewer permission prompt, and one fewer thing
 * to explain to the system settings screen.
 */
class NavigationService : Service() {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private lateinit var alerts: Alerts
    private var tracker: RouteTracker? = null
    private var lastNotificationMs = 0L
    private var arrived = false
    private var trackedRoute: Route? = null
    private var recorder: RideRecorder? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /**
     * Ease off the GPS while the screen is dark.
     *
     * The rider is not reading the map with the phone in a pocket, so a fix a
     * second is wasted battery -- but the accuracy must not drop, because the
     * tracker discards anything worse than 30 m and a balanced-power provider
     * would silently stop navigation. Only the interval changes; off-route still
     * alerts, just within roughly twelve seconds instead of three.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> requestUpdates(SCREEN_OFF_INTERVAL_MS)
                Intent.ACTION_SCREEN_ON -> requestUpdates(SCREEN_ON_INTERVAL_MS)
            }
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            onFix(location)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alerts = Alerts(this)
        createChannel()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val route = NavigationRepository.route.value
        if (route == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Before anything else: the system gives a narrow window to become a
        // foreground service, and missing it tears the service down mid-ride.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.waiting_for_gps), route.name),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        // The recorder comes first: if it resumed an interrupted ride, its
        // coverage has to seed the tracker or the dimmed line starts blank.
        val resumed = startRecording(route)
        recorder = resumed
        tracker = trackerFor(route, resumed)
        trackedRoute = route
        arrived = false
        requestUpdates(SCREEN_ON_INTERVAL_MS)

        // Reversing the route mid-ride replaces it in the repository; the tracker
        // has to be rebuilt or every index it holds refers to the old ordering.
        scope.launch {
            NavigationRepository.route.collect { current ->
                if (current != null && current !== trackedRoute) {
                    trackedRoute = current
                    arrived = false
                    handler.removeCallbacks(autoStop)
                    // Reversing starts a fresh record: the old coverage indices
                    // refer to the other ordering and would be nonsense here.
                    recorder?.discardIfEmpty()
                    val next = startRecording(current)
                    recorder = next
                    tracker = trackerFor(current, next)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Close the record before anything else: this runs on a deliberate stop
        // and on a system kill alike, and it is the last chance to keep the ride.
        tracker?.state?.covered?.let { covered ->
            recorder?.let { if (!it.discardIfEmpty()) it.finish(covered, System.currentTimeMillis()) }
        }
        recorder = null

        scope.cancel()
        handler.removeCallbacks(autoStop)
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { client.removeLocationUpdates(callback) }
        alerts.release()
        NavigationRepository.stop()
        super.onDestroy()
    }

    private fun requestUpdates(intervalMs: Long) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }.onFailure {
            // Permission revoked mid-session; there is nothing useful to do but stop.
            stopSelf()
        }
    }

    private fun onFix(location: Location) {
        val state = tracker?.update(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            timeMs = location.time,
        ) ?: return

        NavigationRepository.publish(state)
        recorder?.record(
            lat = location.latitude,
            lon = location.longitude,
            ele = if (location.hasAltitude()) location.altitude else null,
            timeMs = location.time,
            covered = state.covered,
        )
        alerts.onState(state, System.currentTimeMillis())
        maybeUpdateNotification(state)

        // Once the route is done, keep going briefly in case the finish line was
        // crossed early, then shut down rather than holding the GPS all evening.
        if (state.finished && !arrived) {
            arrived = true
            lastNotificationMs = 0L
            maybeUpdateNotification(state)
            handler.postDelayed(autoStop, AUTO_STOP_DELAY_MS)
        }
    }

    /** Rewriting the notification on every fix is wasteful and makes it flicker. */
    private fun maybeUpdateNotification(state: NavState) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationMs < NOTIFICATION_INTERVAL_MS) return
        lastNotificationMs = now

        val name = NavigationRepository.route.value?.name.orEmpty()
        val title = when {
            state.finished -> getString(R.string.arrived)
            state.offRoute -> "${getString(R.string.off_route)} — ${formatDistance(state.crossTrackM)}"
            else -> buildString {
                append(formatDistance(state.distanceRemainingM))
                state.etaSeconds?.let { append(" · ").append(formatDuration(it)) }
            }
        }

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, name))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.stop_navigation), stop).build()
            )
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // Low importance: the notification is a status readout. The audible
            // part of navigation is the off-route alert, which is separate.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun trackerFor(route: Route, recorder: RideRecorder) = RouteTracker(
        route,
        initialCoverage = CoveredSegments.fromRuns(
            recorder.restoredRuns,
            maxOf(0, route.points.size - 1),
        ),
    )

    /** Begin a record for this route, resuming an interrupted one where it fits. */
    private fun startRecording(route: Route): RideRecorder {
        val store = RideStore(File(filesDir, "rides"))
        val routeId = NavigationRepository.routeId.value ?: route.name

        // An unfinished ride for the same route is the one being resumed; any
        // other is from a different outing and gets closed off into history.
        val existing = store.active()
        val ride = if (existing != null && existing.routeId == routeId &&
            existing.reversed == route.isReversed
        ) {
            existing
        } else {
            store.finaliseAbandoned(System.currentTimeMillis())
            Ride(
                id = UUID.randomUUID().toString(),
                routeId = routeId,
                routeName = route.name,
                reversed = route.isReversed,
                startedAtMs = System.currentTimeMillis(),
                routeDistanceM = route.distanceM,
                routeSegments = maxOf(0, route.points.size - 1),
            )
        }
        return RideRecorder(store, ride)
    }

    companion object {
        const val ACTION_STOP = "de.kettenblatt.STOP"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 1
        private const val SCREEN_ON_INTERVAL_MS = 1_000L
        private const val SCREEN_OFF_INTERVAL_MS = 4_000L
        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val AUTO_STOP_DELAY_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NavigationService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NavigationService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
