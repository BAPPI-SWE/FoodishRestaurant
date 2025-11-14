package com.yumzy.restaurant

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import com.yumzy.restaurant.data.MiniRestaurant

// Change to AndroidViewModel to get Application context
class MainViewModel(app: Application) : AndroidViewModel(app) {

    // Get SharedPreferences
    private val sharedPrefs = app.getSharedPreferences("YumzyPartnerPrefs", Context.MODE_PRIVATE)

    // This holds the restaurant that is currently logged in
    val loggedInRestaurant = mutableStateOf<MiniRestaurant?>(null)

    // This state helps the UI decide the start destination
    val isLoading = mutableStateOf(true)

    init {
        // Check for a saved login as soon as the ViewModel is created
        checkInitialLogin()
    }

    private fun checkInitialLogin() {
        val restaurantId = sharedPrefs.getString("res_id", null)
        if (restaurantId == null) {
            // No saved user, go to login
            isLoading.value = false
            return
        }

        // We have a saved user, reconstruct the MiniRestaurant object
        loggedInRestaurant.value = MiniRestaurant(
            id = restaurantId,
            name = sharedPrefs.getString("res_name", null),
            imageUrl = sharedPrefs.getString("res_imageUrl", null),
            open = sharedPrefs.getString("res_open", "yes"),
            parentCategory = sharedPrefs.getString("res_parentCategory", null),
            password = sharedPrefs.getString("res_password", null)
            // Note: We don't save/load 'availableLocations' here for simplicity
        )
        // We are logged in, go to main app
        isLoading.value = false
    }

    fun saveLogin(restaurant: MiniRestaurant) {
        // Save all necessary fields to SharedPreferences
        sharedPrefs.edit().apply {
            putString("res_id", restaurant.id)
            putString("res_name", restaurant.name)
            putString("res_imageUrl", restaurant.imageUrl)
            putString("res_open", restaurant.open)
            putString("res_parentCategory", restaurant.parentCategory)
            putString("res_password", restaurant.password) // Store password (as in AuthScreen)
            apply()
        }
        // Update the in-memory state
        loggedInRestaurant.value = restaurant
    }

    fun clearLogin() {
        // Clear all saved data from SharedPreferences
        sharedPrefs.edit().clear().apply()
        // Update the in-memory state
        loggedInRestaurant.value = null
    }
}