package dev.addblocker.adshield

import android.app.Application
import dev.addblocker.adshield.data.Prefs
import dev.addblocker.adshield.data.RulesRepository
import dev.addblocker.adshield.data.StatsRepository

class AdShieldApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        StatsRepository.init()
        RulesRepository.init(this)
    }
}
