package com.yumzy.restaurant.screens.manage

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.data.MiniRestaurant
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageStoreScreen(
    restaurant: MiniRestaurant,
    onManageItemsClicked: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onSignOut: () -> Unit
) {
    // FIX: Fetch the current status from Firestore instead of using cached value
    var isOpen by remember { mutableStateOf(restaurant.open.equals("yes", true)) }
    var isLoading by remember { mutableStateOf(true) }
    val context = LocalContext.current
    var isUpdating by remember { mutableStateOf(false) }

    // Fetch current status from Firestore when screen loads
    LaunchedEffect(restaurant.id) {
        try {
            val doc = Firebase.firestore.collection("mini_restaurants")
                .document(restaurant.id)
                .get()
                .await()

            val currentStatus = doc.getString("open") ?: "yes"
            isOpen = currentStatus.equals("yes", ignoreCase = true)
        } catch (e: Exception) {
            Toast.makeText(context, "Error loading status: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isLoading = false
        }
    }

    fun updateStatus(newStatus: Boolean) {
        isUpdating = true
        val newStatusString = if (newStatus) "yes" else "no"
        Firebase.firestore.collection("mini_restaurants").document(restaurant.id)
            .update("open", newStatusString)
            .addOnSuccessListener {
                isOpen = newStatus // Update local state only on success
                isUpdating = false
                Toast.makeText(context, "Status updated", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                isUpdating = false
                Toast.makeText(context, "Error updating status", Toast.LENGTH_SHORT).show()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Your Store") },
                actions = {
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Default.Logout, contentDescription = "Sign Out")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Show loading indicator while fetching status
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Re-usable card showing restaurant info
                MiniRestaurantPartnerCard(
                    restaurant = restaurant,
                    isOpen = isOpen,
                    onStatusChange = { newStatus ->
                        if (!isUpdating) {
                            updateStatus(newStatus)
                        }
                    }
                )
            }

            // "Manage Items" button
            Button(
                onClick = onManageItemsClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("Manage Store Items", fontSize = 16.sp)
            }

            OutlinedButton(
                onClick = onNavigateToHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.History, contentDescription = "History")
                Spacer(Modifier.width(8.dp))
                Text("View Delivery History", fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun MiniRestaurantPartnerCard(
    restaurant: MiniRestaurant,
    isOpen: Boolean,
    onStatusChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = restaurant.imageUrl ?: "",
                contentDescription = restaurant.name ?: "Restaurant Image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
            )
            if (!isOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Closed", tint = Color.White, modifier = Modifier.size(48.dp))
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isOpen) "Open" else "Closed", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = isOpen,
                        onCheckedChange = onStatusChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
                Text(
                    text = restaurant.name ?: "Unnamed Restaurant",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 22.sp
                )
            }
        }
    }
}