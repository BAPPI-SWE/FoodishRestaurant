package com.yumzy.restaurant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
        // UPDATED: Added USE_FULL_SCREEN_INTENT permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.USE_FULL_SCREEN_INTENT
                ),
                101
            )
        }

        setContent {
            YumzyRestaurantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Gate the whole app behind the "Display over other apps" permission.
                    // Until it's granted, the user only sees the request screen.
                    OverlayPermissionGate {
                        AppEntry(
                            viewModel = mainViewModel,
                            onStartService = { startOrderService() },
                            onStopService = { stopOrderService() }
                        )
                    }
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

/**
 * Blocks access to the rest of the app until the user has granted the
 * "Display over other apps" (SYSTEM_ALERT_WINDOW) permission, which the order alarm popup
 * needs in order to launch itself over other apps / the launcher.
 *
 * It re-checks the permission every time the app resumes, so when the user returns from the
 * system Settings screen after enabling it, the app immediately unlocks with no restart needed.
 * On Android 5.1 and below this permission is granted at install time, so the gate passes
 * straight through.
 */
@Composable
fun OverlayPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    var granted by remember { mutableStateOf(hasOverlayPermission()) }

    // Re-check whenever the app comes back to the foreground (e.g. returning from Settings).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasOverlayPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        content()
    } else {
        OverlayPermissionRequestScreen(
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )
    }
}

@Composable
fun OverlayPermissionRequestScreen(onOpenSettings: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = null,
                modifier = Modifier.height(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Permission Required",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp)
            )

            Text(
                text = "To make sure you never miss an order, this app needs the " +
                        "\"Display over other apps\" permission. This lets the new-order alarm " +
                        "pop up on your screen even when you are using another app.\n\n" +
                        "Please enable it to continue.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )

            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .padding(top = 32.dp)
                    .height(52.dp)
            ) {
                Text("Enable Permission", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
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