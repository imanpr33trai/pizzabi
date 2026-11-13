package com.example

import android.app.Application
import com.example.core.di.AppContainer

class PizzoraApplication : Application() {
  lateinit var appContainer: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    appContainer = AppContainer(this)
  }
}
