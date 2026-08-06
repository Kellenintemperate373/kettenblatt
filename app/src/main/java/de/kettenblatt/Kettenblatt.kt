package de.kettenblatt

import android.app.Application
import android.preference.PreferenceManager
import org.osmdroid.config.Configuration

class Kettenblatt : Application() {
    override fun onCreate() {
        super.onCreate()

        Configuration.getInstance().apply {
            load(this@Kettenblatt, PreferenceManager.getDefaultSharedPreferences(this@Kettenblatt))
            // osmdroid's Mapnik source declares FLAG_USER_AGENT_MEANINGFUL, and
            // tile.openstreetmap.org rejects the library's default user agent.
            // Leave this unset and every tile silently fails to load -- a grey
            // map with nothing in the log to explain it.
            userAgentValue = BuildConfig.APPLICATION_ID
        }
    }
}
