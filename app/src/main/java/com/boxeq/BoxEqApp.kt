package com.boxeq

import android.app.Application
import android.content.Context
import com.boxeq.data.SettingsRepository
import com.boxeq.engine.EqEngine
import com.boxeq.shizuku.PlaybackMonitor
import com.boxeq.shizuku.ShizukuController

/** Process-wide singletons. The broadcast receiver can cold-start the process, so everything lives here. */
class AppGraph(context: Context) {
    val repository = SettingsRepository(context)
    val engine = EqEngine(repository)
    val shizuku = ShizukuController(context)
    val monitor = PlaybackMonitor(context, engine, shizuku)
}

class BoxEqApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.shizuku.start()
        graph.monitor.start()
    }
}

val Context.graph: AppGraph
    get() = (applicationContext as BoxEqApp).graph
