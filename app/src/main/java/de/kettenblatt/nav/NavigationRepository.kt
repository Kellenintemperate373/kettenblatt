package de.kettenblatt.nav

import de.kettenblatt.data.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single place the foreground service and the UI meet.
 *
 * A plain object rather than an injected singleton: there is exactly one
 * navigation session at a time, and a DI framework would add wiring without
 * removing any.
 */
object NavigationRepository {

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state.asStateFlow()

    private val _routeId = MutableStateFlow<String?>(null)
    val routeId: StateFlow<String?> = _routeId.asStateFlow()

    val isNavigating: Boolean get() = _route.value != null

    fun start(routeId: String, route: Route) {
        _routeId.value = routeId
        _route.value = route
        _state.value = null
    }

    /**
     * Swap the route without ending the session, for reversing mid-ride.
     *
     * The published state is cleared because every index in it refers to the old
     * ordering; the service rebuilds its tracker from the new route and the first
     * fix after this repopulates it.
     */
    fun replaceRoute(route: Route) {
        _state.value = null
        _route.value = route
    }

    fun publish(state: NavState) {
        _state.value = state
    }

    fun stop() {
        _routeId.value = null
        _route.value = null
        _state.value = null
    }
}
