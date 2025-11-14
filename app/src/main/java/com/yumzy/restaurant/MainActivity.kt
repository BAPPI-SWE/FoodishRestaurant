package com.yumzy.restaurant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yumzy.restaurant.auth.AuthScreen
import com.yumzy.restaurant.navigation.AppNavigation
import com.yumzy.restaurant.services.OrderMonitorService
import com.yumzy.restaurant.ui.theme.YumzyRestaurantTheme

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request Notification Permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        setContent {
            YumzyRestaurantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppEntry(
                        viewModel = mainViewModel,
                        onStartService = { startOrderService() },
                        onStopService = { stopOrderService() }
                    )
                }
            }
        }
    }

    private fun startOrderService() {
        val serviceIntent = Intent(this, OrderMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopOrderService() {
        val serviceIntent = Intent(this, OrderMonitorService::class.java)
        stopService(serviceIntent)
    }
}

@Composable
fun AppEntry(
    viewModel: MainViewModel,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val navController = rememberNavController()
    val isLoading by viewModel.isLoading
    val loggedInRestaurant by viewModel.loggedInRestaurant

    // Show loading spinner while checking SharedPreferences
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val startDestination = if (loggedInRestaurant != null) "main_app" else "auth"

    NavHost(navController = navController, startDestination = startDestination) {
        // Login Screen
        composable("auth") {
            AuthScreen(
                onLoginSuccess = { restaurant ->
                    viewModel.saveLogin(restaurant)
                    navController.navigate("main_app") {
                        popUpTo("auth") { inclusive = true }
                    }
                }
            )
        }

        // Main App Screen
        composable("main_app") {
            // Start the background service when entering main app
            LaunchedEffect(Unit) {
                onStartService()
            }

            val currentRestaurant = viewModel.loggedInRestaurant.value
            if (currentRestaurant != null) {
                AppNavigation(
                    restaurant = currentRestaurant,
                    onSignOut = {
                        // Stop the service on sign out
                        onStopService()
                        viewModel.clearLogin()
                        navController.navigate("auth") {
                            popUpTo("main_app") { inclusive = true }
                        }
                    }
                )
            } else {
                navController.navigate("auth") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }
}