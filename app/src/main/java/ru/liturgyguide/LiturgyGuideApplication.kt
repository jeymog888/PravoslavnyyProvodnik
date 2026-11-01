package ru.liturgyguide

import android.app.Application
import ru.liturgyguide.di.AppContainer

class LiturgyGuideApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}