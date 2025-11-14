package com.yumzy.restaurant.screens.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.data.MiniRestaurant
import com.yumzy.restaurant.data.OrderItemDetail
import com.yumzy.restaurant.data.PartnerOrder
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveOrdersScreen(loggedInRestaurant: MiniRestaurant) {
    var partnerOrders by remember { mutableStateOf<List<PartnerOrder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // ---
    // (FIX 1)
    // Change key back to name, since we filter by name
    // ---
    LaunchedEffect(loggedInRestaurant.name) {
        val db = Firebase.firestore
        val listener = db.collection("orders")
            // Listen to orders that are not yet delivered or cancelled
            .whereIn("orderStatus", listOf("Pending", "Accepted", "Preparing", "On the way"))
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    isLoading = false
                    return@addSnapshotListener
                }

                if (snapshot != null) {

                    // This typo is fixed (this is correct)
                    val newPartnerOrders = mutableListOf<PartnerOrder>()

                    // ---
                    // (FIX 2)
                    // Get the partner's NAME, not ID
                    // ---
                    val partnerName = loggedInRestaurant.name

                    for (doc in snapshot.documents) {
                        // Get all items from the order
                        val allItems = doc.get("items") as? List<Map<String, Any>> ?: emptyList()

                        // Filter to find items ONLY for this restaurant
                        val partnerItems = allItems.mapNotNull { itemMap ->

                            // ---
                            // (FIX 3)
                            // Get the "miniResName" (NAME) field from the item map.
                            // ---
                            val itemMiniResName = itemMap["miniResName"] as? String ?: ""

                            // ---
                            // (FIX 4)
                            // Compare the NAMES, with trim() and ignoreCase = true
                            // ---
                            val cleanPartnerName = partnerName?.trim()
                            val cleanItemResName = itemMiniResName.trim()

                            if (!cleanPartnerName.isNullOrBlank() && cleanItemResName.isNotEmpty() && cleanItemResName.equals(cleanPartnerName, ignoreCase = true)) {
                                // This item belongs to the logged-in partner
                                OrderItemDetail(
                                    name = itemMap["itemName"] as? String ?: "Unknown",
                                    quantity = (itemMap["quantity"] as? Number)?.toInt() ?: 0,
                                    price = (itemMap["itemPrice"] as? Number)?.toDouble() ?: 0.0,
                                    miniResName = itemMiniResName // Use the name we just got
                                )
                            } else {
                                null // This item is not for this partner
                            }
                        }

                        // If we found any items for this partner, create a PartnerOrder
                        if (partnerItems.isNotEmpty()) {
                            val order = doc.toObject(PartnerOrder::class.java)?.copy(
                                id = doc.id,
                                items = partnerItems // Set the FILTERED list of items
                            )
                            if (order != null) {
                                newPartnerOrders.add(order)
                            }
                        }
                    }
                    // We sort the list here, in Kotlin, which is safe.
                    partnerOrders = newPartnerOrders.sortedByDescending { it.createdAt }
                }
                isLoading = false
            }

        // Remember to remove the listener when the composable is destroyed
        // (This part is handled by LaunchedEffect's coroutine scope)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Orders for ${loggedInRestaurant.name}") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (partnerOrders.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Storefront, "No orders", Modifier.size(64.dp), tint = Color.Gray)
                        Spacer(Modifier.height(16.dp))
                        Text("No active orders for your store right now.", color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(partnerOrders) { order ->
                        PartnerOrderCard(order = order)
                    }
                }
            }
        }
    }
}

@Composable
fun PartnerOrderCard(order: PartnerOrder) {
    val sdf = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
    val statusColor = when (order.orderStatus) {
        "Pending" -> Color(0xFF757575)
        "Accepted", "Preparing" -> Color(0xFF0D47A1)
        "On the way" -> Color(0xFFE65100)
        "Delivered" -> Color(0xFF1B5E20)
        else -> Color.Black
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(3.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header
            Row(
// ... existingGg code ...
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Order #${order.id.take(6)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        sdf.format(order.createdAt.toDate()),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Text(
                    "৳${order.totalPrice}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, // Fixed from GgFontWeight
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }

            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            // Your Items in this Order
            Text(
                "Your Items:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))

            // This list ONLY contains this partner's items
            order.items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${item.quantity}x ${item.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "৳${item.price * item.quantity}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            // Customer and Location Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    InfoRow(label = "Customer:", value = order.userName)
                    InfoRow(label = "Location:", value = order.userSubLocation)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Status Badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusColor.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, RoundedCornerShape(50))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = order.orderStatus,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.width(80.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}