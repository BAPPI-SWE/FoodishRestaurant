package com.yumzy.restaurant.screens.history

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.data.OrderItemDetail
import com.yumzy.restaurant.data.PartnerOrder
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryHistoryScreen(
    restaurantName: String,
    onBack: () -> Unit
) {
    var completedOrders by remember { mutableStateOf<List<PartnerOrder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchText by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf<Long?>(null) }
    var endDate by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current

    fun showDatePicker(onDateSelected: (Long) -> Unit) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                calendar.set(year, month, dayOfMonth)
                onDateSelected(calendar.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    LaunchedEffect(restaurantName) {
        if (restaurantName.isBlank()) {
            isLoading = false
            return@LaunchedEffect
        }

        val db = Firebase.firestore
        db.collection("orders")
            .whereEqualTo("orderStatus", "Delivered")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    isLoading = false
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val partnerOrders = mutableListOf<PartnerOrder>()

                    for (doc in snapshot.documents) {
                        val allItems = doc.get("items") as? List<Map<String, Any>> ?: emptyList()

                        val partnerItems = allItems.mapNotNull { itemMap ->
                            val itemMiniResName = itemMap["miniResName"] as? String ?: ""
                            val cleanPartnerName = restaurantName.trim()
                            val cleanItemResName = itemMiniResName.trim()

                            if (cleanItemResName.isNotEmpty() && cleanItemResName.equals(cleanPartnerName, ignoreCase = true)) {
                                OrderItemDetail(
                                    name = itemMap["itemName"] as? String ?: "Unknown",
                                    quantity = (itemMap["quantity"] as? Number)?.toInt() ?: 0,
                                    price = (itemMap["price"] as? Number)?.toDouble() ?: 0.0,
                                    miniResName = itemMiniResName
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
                                partnerOrders.add(order)
                            }
                        }
                    }
                    completedOrders = partnerOrders
                }
                isLoading = false
            }
    }

    val filteredOrders by remember(searchText, completedOrders, startDate, endDate) {
        derivedStateOf {
            val calendar = Calendar.getInstance()

            val startMillis = startDate?.let {
                calendar.timeInMillis = it
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.timeInMillis
            }

            val endMillis = endDate?.let {
                calendar.timeInMillis = it
                calendar.set(Calendar.HOUR_OF_DAY, 23)
                calendar.set(Calendar.MINUTE, 59)
                calendar.set(Calendar.SECOND, 59)
                calendar.timeInMillis
            }

            var orders = completedOrders

            if (startMillis != null) {
                orders = orders.filter { it.createdAt.toDate().time >= startMillis }
            }
            if (endMillis != null) {
                orders = orders.filter { it.createdAt.toDate().time <= endMillis }
            }

            if (searchText.isNotBlank()) {
                orders.filter { order ->
                    order.userName.contains(searchText, ignoreCase = true) ||
                            order.id.contains(searchText, ignoreCase = true)
                }
            } else {
                orders
            }
        }
    }

    val totalDeliveries = filteredOrders.size
    val totalEarnings = filteredOrders.sumOf { partnerOrder ->
        partnerOrder.items.sumOf { item -> item.price * item.quantity }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delivery History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search History") },
                placeholder = { Text("Search by customer name or order ID...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") }
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { showDatePicker { startDate = it } }, modifier = Modifier.weight(1f)) {
                    Text(startDate?.let { SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(it)) } ?: "Start Date")
                }
                Button(onClick = { showDatePicker { endDate = it } }, modifier = Modifier.weight(1f)) {
                    Text(endDate?.let { SimpleDateFormat("dd MMM yy", Locale.getDefault()).format(Date(it)) } ?: "End Date")
                }
                TextButton(onClick = {
                    startDate = null
                    endDate = null
                }) {
                    Text("Clear")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (completedOrders.isEmpty() && searchText.isBlank()){
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("You have no completed deliveries yet.", color = Color.Gray)
                }
            }
            else {
                SummaryCard(
                    totalDeliveries = totalDeliveries,
                    totalEarnings = totalEarnings
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (filteredOrders.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val emptyText = when {
                            searchText.isNotBlank() -> "No deliveries found for \"$searchText\""
                            else -> "No deliveries found in the selected date range."
                        }
                        Text(emptyText, color = Color.Gray)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(filteredOrders) { order ->
                            PartnerHistoryOrderCard(order = order)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryCard(totalDeliveries: Int, totalEarnings: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Total Orders",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = totalDeliveries.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Total Earnings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "৳${"%.2f".format(totalEarnings)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun PartnerHistoryOrderCard(order: PartnerOrder) {
    val sdf = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }
    val partnerEarnings = order.items.sumOf { it.price * it.quantity }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Order #${order.id.take(6)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Customer: ${order.userName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Your Cut: ৳$partnerEarnings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Delivered on ${sdf.format(order.createdAt.toDate())}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))

            Text(
                "Your Items in this Order:",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))

            order.items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "• ${item.quantity}x ${item.name}",
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
        }
    }
}