package ir.mahditavakoli.mia

import android.app.Application
import ir.mahditavakoli.mia.network.NetworkModule
import ir.mahditavakoli.mia.notify.AgentCompletionWorker
import ir.mahditavakoli.mia.notify.AgentNotifications

class MIAApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NetworkModule.init(this)
        // Both are idempotent and cheap, and both need NetworkModule already initialised: the
        // scheduler asks it whether GitHub is configured at all.
        AgentNotifications.ensureChannel(this)
        AgentCompletionWorker.schedule(this)
    }
}
