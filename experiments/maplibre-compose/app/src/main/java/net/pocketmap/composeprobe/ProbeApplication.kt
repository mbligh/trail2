package net.pocketmap.composeprobe

import android.app.Application
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider

/**
 * MapLibre Compose's default renderer is a singleton; configure it once per
 * process, rather than on every Activity onCreate / orientation change.
 */
class ProbeApplication : Application() {
    internal lateinit var offlineResources: OfflineProbeResources
        private set

    override fun onCreate() {
        super.onCreate()
        offlineResources = OfflineProbeResources(applicationContext)
        val provider = MapResourceProvider(
            accepts = { offlineResources.accepts(it.url) },
            load = { request ->
                val data = offlineResources.load(request.url)
                if (data == null) MapResourceLoad.NoContent()
                else MapResourceLoad.Bytes(data)
            }
        )
        DefaultMapRuntime.configure { resourceProvider = provider }
    }
}
