package com.example

import android.app.Application
import androidx.work.Configuration
import com.example.core.di.AppContainer

class PizzoraApplication : Application(), Configuration.Provider {
  val appContainer: AppContainer by lazy {
    AppContainer(this)
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(appContainer.workerFactory)
      .build()

  override fun onCreate() {
    super.onCreate()
    appContainer.notificationHelper.ensureChannels()
  }
}
