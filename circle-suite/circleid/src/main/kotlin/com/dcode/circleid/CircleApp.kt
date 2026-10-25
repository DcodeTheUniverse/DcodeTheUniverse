package com.dcode.circleid

import android.app.Application
import com.dcode.circleid.face.FaceEmbedder
import com.dcode.circleid.security.CircleKeystore
import com.dcode.circleid.security.CirclePrefs
import com.dcode.circleid.security.HistoryStore
import com.dcode.circleid.security.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CircleGraph(app: Application) {
    val keystore = CircleKeystore(app)
    val profiles = ProfileStore(app, keystore)
    val history = HistoryStore(app, keystore)
    val prefs = CirclePrefs(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The face model is loaded lazily on a background thread the first time a camera screen opens. */
    val embedder: FaceEmbedder by lazy { FaceEmbedder(app) }

    val isEnrolled: Boolean get() = profiles.isEnrolled && prefs.circleId != null

    fun resetEverything() {
        profiles.wipe()
        keystore.wipeAll()
        history.clear()
        prefs.clear()
    }
}

class CircleApp : Application() {
    lateinit var graph: CircleGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = CircleGraph(this)
        graph.scope.launch {
            graph.history.load()
            runCatching { graph.embedder }
        }
    }
}

val android.content.Context.graph: CircleGraph get() = (applicationContext as CircleApp).graph
