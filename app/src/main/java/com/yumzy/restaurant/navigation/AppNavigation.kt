package com.yumzy.restaurant.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yumzy.restaurant.data.MiniRestaurant
import com.yumzy.restaurant.screens.manage.ManageStoreScreen
import com.yumzy.restaurant.screens.manage.RestaurantItemListScreen
import com.yumzy.restaurant.screens.manage.RestaurantSubCategoryScreen
import com.yumzy.restaurant.screens.orders.LiveOrdersScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// Defines the screens in our app
sealed class Screen(val route: String) {
    // Bottom Nav Screens
    data object Orders : Screen("orders")
    data object Manage : Screen("manage")

    // Other screens (for navigation)
    data object SubCategories : Screen("sub_categories")
    data object Items : Screen("items/{subCategoryName}") {
        fun createRoute(subCategoryName: String): String {
            val encodedName = URLEncoder.encode(subCategoryName, StandardCharsets.UTF_8.toString())
            return "items/$encodedName"
        }
    }
}

data class BottomNavItem(val screen: Screen, val label: String, val icon: ImageVector)

@Composable
fun AppNavigation(
    restaurant: MiniRestaurant,
    onSignOut: () -> Unit
) {
    val navController = rememberNavController()

    val items = listOf(
        BottomNavItem(Screen.Orders, "Orders", Icons.Default.List),
        BottomNavItem(Screen.Manage, "Manage", Icons.Default.Storefront)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                        onClick = {
                            navController.navigate(item.screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Orders.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Live Orders Tab
            composable(Screen.Orders.route) {
                LiveOrdersScreen(loggedInRestaurant = restaurant)
            }

            // Manage Store Tab
            composable(Screen.Manage.route) {
                ManageStoreScreen(
                    restaurant = restaurant,
                    onManageItemsClicked = {
                        navController.navigate(Screen.SubCategories.route)
                    },
                    onSignOut = onSignOut
                )
            }

            // Sub-Category Screen (navigated from Manage)
            composable(Screen.SubCategories.route) {
                // --- UPDATED: Handle null name and category ---
                RestaurantSubCategoryScreen(
                    miniResId = restaurant.id,
                    miniResName = restaurant.name ?: "Your Store", // <-- FIX HERE
                    parentCategoryId = restaurant.parentCategory ?: "", // <-- FIX HERE
                    onSubCategoryClicked = { subCatName ->
                        navController.navigate(Screen.Items.createRoute(subCatName))
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            // Item List Screen (navigated from Sub-Category)
            composable(
                route = Screen.Items.route,
                arguments = listOf(navArgument("subCategoryName") { type = NavType.StringType })
            ) { backStackEntry ->
                val encodedName = backStackEntry.arguments?.getString("subCategoryName") ?: ""
                val subCategoryName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8.toString())
                RestaurantItemListScreen(
                    miniResId = restaurant.id,
                    subCategoryName = subCategoryName,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}