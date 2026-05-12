package com.yumzy.restaurant.screens.orders

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.data.MiniRestaurant
import com.yumzy.restaurant.data.OrderItemDetail
import com.yumzy.restaurant.data.PartnerOrder
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveOrdersScreen(loggedInRestaurant: MiniRestaurant) {
    var partnerOrders by remember { mutableStateOf<List<PartnerOrder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(loggedInRestaurant.name) {
        val db = Firebase.firestore
        // FIX 1: Trim the partner name once here to avoid repeated trimming
        val partnerName = loggedInRestaurant.name?.trim() ?: ""

        if (partnerName.isEmpty()) {
            errorMessage = "Restaurant name is missing. Please re-login."
            isLoading = false
            return@LaunchedEffect
        }

        db.collection("orders")
            .whereIn("orderStatus", listOf("Pending", "Accepted", "Preparing", "On the way"))
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    errorMessage = "Error loading orders: ${error.message}"
                    isLoading = false
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val newPartnerOrders = mutableListOf<PartnerOrder>()

                    for (doc in snapshot.documents) {
                        try {
                            val allItems = doc.get("items") as? List<Map<String, Any>> ?: emptyList()

                            // FIX 2: Carry partnerStatus from Firestore into OrderItemDetail
                            val partnerItems = allItems.mapNotNull { itemMap ->
                                val itemMiniResName = (itemMap["miniResName"] as? String)?.trim() ?: ""

                                if (itemMiniResName.equals(partnerName, ignoreCase = true)) {
                                    OrderItemDetail(
                                        name = itemMap["itemName"] as? String ?: "Unknown",
                                        quantity = (itemMap["quantity"] as? Number)?.toInt() ?: 0,
                                        price = (itemMap["price"] as? Number)?.toDouble() ?: 0.0,
                                        miniResName = itemMiniResName,
                                        // FIX 3: Read partnerStatus from Firestore so button state persists
                                        partnerStatus = itemMap["partnerStatus"] as? String
                                    )
                                } else {
                                    null
                                }
                            }

                            if (partnerItems.isNotEmpty()) {
                                val order = doc.toObject(PartnerOrder::class.java)?.copy(
                                    id = doc.id,
                                    items = partnerItems
                                )
                                if (order != null) {
                                    newPartnerOrders.add(order)
                                }
                            }
                        } catch (e: Exception) {
                            // Skip malformed documents silently
                        }
                    }

                    partnerOrders = newPartnerOrders.sortedByDescending { it.createdAt }
                    errorMessage = null
                }
                isLoading = false
            }
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
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            errorMessage ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                partnerOrders.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Storefront, "No orders", Modifier.size(64.dp), tint = Color.Gray)
                            Spacer(Modifier.height(16.dp))
                            Text("No active orders for your store right now.", color = Color.Gray)
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(partnerOrders, key = { it.id }) { order ->
                            PartnerOrderCard(
                                order = order,
                                partnerName = loggedInRestaurant.name ?: ""
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PartnerOrderCard(order: PartnerOrder, partnerName: String) {
    val sdf = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
    val context = LocalContext.current
    var isUpdating by remember { mutableStateOf(false) }

    // FIX 4: Initialize button state from Firestore data (partnerStatus on the item),
    // not from local remember that resets to null on every recomposition/navigation.
    var currentPartnerStatus by remember(order.id) {
        mutableStateOf(order.items.firstOrNull()?.partnerStatus)
    }

    val statusColor = when (order.orderStatus) {
        "Pending" -> Color(0xFF757575)
        "Accepted", "Preparing" -> Color(0xFF0D47A1)
        "On the way" -> Color(0xFFE65100)
        "Delivered" -> Color(0xFF1B5E20)
        else -> Color.Black
    }

    suspend fun updatePartnerStatus(orderId: String, newStatus: String) {
        isUpdating = true
        try {
            val db = Firebase.firestore
            val orderRef = db.collection("orders").document(orderId)
            val orderDoc = orderRef.get().await()

            val items = orderDoc.get("items") as? List<Map<String, Any>> ?: emptyList()
            val updatedItems = items.map { item ->
                val itemMiniResName = item["miniResName"] as? String ?: ""
                if (itemMiniResName.trim().equals(partnerName.trim(), ignoreCase = true)) {
                    item.toMutableMap().apply { put("partnerStatus", newStatus) }
                } else {
                    item
                }
            }

            orderRef.update("items", updatedItems).await()
            currentPartnerStatus = newStatus
            Toast.makeText(context, "Status updated to $newStatus", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to update: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            isUpdating = false
        }
    }

    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(3.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {

            // Order Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Order #${order.id.take(6)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sdf.format(order.createdAt?.toDate() ?: Date()),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Text(
                    "৳${order.items.sumOf { it.price * it.quantity }}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(12.dp))

            // Items
            Text("Your Items:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))

            order.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
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

            // Customer Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    InfoRow(label = "Customer:", value = order.userName)
                    InfoRow(label = "Location:", value = order.userSubLocation)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = "Phone",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            order.userPhone,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Row {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.userPhone}"))
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.Call, contentDescription = "Call Customer", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        try {
                            var formattedNumber = order.userPhone.replace(Regex("[^0-9+]"), "")
                            if (formattedNumber.startsWith("01") && formattedNumber.length == 11) {
                                formattedNumber = "+880${formattedNumber.substring(1)}"
                            }
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("https://api.whatsapp.com/send?phone=$formattedNumber")
                                setPackage("com.whatsapp")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "WhatsApp is not installed.", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Chat, contentDescription = "WhatsApp Customer", tint = Color(0xFF25D366))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Order Status Badge
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
                    Box(modifier = Modifier.size(10.dp).background(statusColor, RoundedCornerShape(50)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = order.orderStatus,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // FIX 5: Partner Status Action Buttons — visually reflect current state clearly
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val acceptDone = currentPartnerStatus == "Accepted" || currentPartnerStatus == "Ready"
                val readyDone = currentPartnerStatus == "Ready"

                OutlinedButton(
                    onClick = { scope.launch { updatePartnerStatus(order.id, "Accepted") } },
                    modifier = Modifier.weight(1f),
                    enabled = !isUpdating && !acceptDone,
                    colors = if (acceptDone) ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF0D47A1).copy(alpha = 0.1f)
                    ) else ButtonDefaults.outlinedButtonColors()
                ) {
                    if (isUpdating && currentPartnerStatus == null) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Text(if (acceptDone) "✓ Accepted" else "Accept")
                    }
                }

                Button(
                    onClick = { scope.launch { updatePartnerStatus(order.id, "Ready") } },
                    modifier = Modifier.weight(1f),
                    enabled = !isUpdating && !readyDone,
                    colors = if (readyDone) ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32)
                    ) else ButtonDefaults.buttonColors()
                ) {
                    if (isUpdating && currentPartnerStatus == "Accepted") {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(if (readyDone) "✓ Ready" else "Ready")
                    }
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