package ir.mahditavakoli.mia

import android.app.Application
import ir.mahditavakoli.mia.network.NetworkModule

class MIAApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NetworkModule.init(this)
    }
}
