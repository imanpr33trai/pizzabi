package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.auth.AuthViewModel
import com.example.navigation.PizzoraApp

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val container = (application as PizzoraApplication).appContainer

    setContent {
      val authViewModel: AuthViewModel = viewModel(factory = container.authViewModelFactory())
      PizzoraApp(authViewModel = authViewModel)
    }
  }
}
