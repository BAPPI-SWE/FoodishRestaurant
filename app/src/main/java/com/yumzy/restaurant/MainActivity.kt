package com.yumzy.restaurant

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yumzy.restaurant.auth.AuthScreen
import com.yumzy.restaurant.navigation.AppNavigation
import com.yumzy.restaurant.ui.theme.YumzyRestaurantTheme

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YumzyRestaurantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Pass the viewmodel, not the state
                    AppEntry(viewModel = mainViewModel)
                }
            }
        }
    }
}

@Composable
fun AppEntry(viewModel: MainViewModel) {
    val navController = rememberNavController()

    // Get the state values from the ViewModel
    val isLoading by viewModel.isLoading
    val loggedInRestaurant by viewModel.loggedInRestaurant

    // Show a loading spinner while the ViewModel checks SharedPreferences
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return // Don't proceed until loading is finished
    }

    // Determine the start destination *after* loading
    val startDestination = if (loggedInRestaurant != null) "main_app" else "auth"

    NavHost(navController = navController, startDestination = startDestination) {
        // Login Screen
        composable("auth") {
            AuthScreen(
                onLoginSuccess = { restaurant ->
                    // Save the login using the ViewModel
                    viewModel.saveLogin(restaurant)

                    // Navigate to the main app and clear the login screen from history
                    navController.navigate("main_app") {
                        popUpTo("auth") { inclusive = true }
                    }
                }
            )
        }

        // Main App Screen (with bottom navigation)
        composable("main_app") {
            // Get the restaurant from the ViewModel's state
            val currentRestaurant = viewModel.loggedInRestaurant.value
            if (currentRestaurant != null) {
                // Pass the restaurant details to the main app navigation
                AppNavigation(
                    restaurant = currentRestaurant,
                    onSignOut = {
                        // Clear the login using the ViewModel
                        viewModel.clearLogin()

                        navController.navigate("auth") {
                            popUpTo("main_app") { inclusive = true }
                        }
                    }
                )
            } else {
                // This case should now only happen if state is lost unexpectedly
                navController.navigate("auth") {
                    popUpTo(0) { inclusive = true } // Clear entire back stack
                }
            }
        }
    }
}