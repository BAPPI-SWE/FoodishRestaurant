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
    var loadError by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf<Long?>(null) }
    var endDate by remember { mutableStateOf<Long?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val tabs = listOf("Orders", "Item Summary")

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
            loadError = "Restaurant name is missing. Please re-login."
            isLoading = false
            return@LaunchedEffect
        }

        val partnerName = restaurantName.trim().replace(Regex("\\s+"), " ")
        val db = Firebase.firestore
        db.collection("orders")
            .whereEqualTo("orderStatus", "Delivered")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            // FIX: removed the limit() entirely. The admin app's revenue query has no cap at
            // all (a plain .get() over every Delivered order), so any limit here - even a
            // generous one - will make a long-running store's totals silently fall short of
            // admin's the moment it passes that cap. Matching admin exactly means fetching
            // every Delivered order, same as admin does.
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    loadError = "Error loading history: ${error.message}"
                    isLoading = false
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val partnerOrders = mutableListOf<PartnerOrder>()

                    for (doc in snapshot.documents) {
                        try {
                            val allItems = doc.get("items") as? List<Map<String, Any>> ?: emptyList()

                            val partnerItems = allItems.mapNotNull { itemMap ->
                                val rawName = itemMap["miniResName"] as? String ?: ""
                                // FIX: normalize whitespace (not just trim) so a stray double
                                // space or odd whitespace character in the stored item name
                                // doesn't silently drop it from this restaurant's totals - the
                                // same class of bug that was hiding items in Live Orders too.
                                val normalizedItemRes = rawName.trim().replace(Regex("\\s+"), " ")

                                if (normalizedItemRes.isNotEmpty() && normalizedItemRes.equals(partnerName, ignoreCase = true)) {
                                    OrderItemDetail(
                                        name = itemMap["itemName"] as? String ?: "Unknown",
                                        quantity = (itemMap["quantity"] as? Number)?.toInt() ?: 0,
                                        price = (itemMap["price"] as? Number)?.toDouble() ?: 0.0,
                                        miniResName = rawName
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
                        } catch (e: Exception) {
                            // Skip malformed documents rather than crashing the whole listener.
                        }
                    }
                    completedOrders = partnerOrders
                    loadError = null
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

    // Item-level aggregation, shared by the "Item Summary" tab. This mirrors the kind of
    // per-item sales rollup the admin app shows, but scoped to this partner's filtered orders.
    data class ItemSummaryRow(
        val name: String,
        val quantity: Int,
        val revenue: Double
    )

    val itemSummary by remember(filteredOrders) {
        derivedStateOf {
            filteredOrders
                .flatMap { it.items }
                .groupBy { it.name }
                .map { (name, items) ->
                    ItemSummaryRow(
                        name = name,
                        quantity = items.sumOf { it.quantity },
                        revenue = items.sumOf { it.price * it.quantity }
                    )
                }
                .sortedByDescending { it.revenue }
        }
    }

    val totalDeliveries = filteredOrders.size
    val totalEarnings = filteredOrders.sumOf { partnerOrder ->
        partnerOrder.items.sumOf { item -> item.price * item.quantity }
    }
    val totalItems = filteredOrders.sumOf { partnerOrder ->
        partnerOrder.items.sumOf { item -> item.quantity }
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
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
            }

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                when {
                    isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    loadError != null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(loadError ?: "Unknown error", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    completedOrders.isEmpty() && searchText.isBlank() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("You have no completed deliveries yet.", color = Color.Gray)
                        }
                    }
                    else -> {
                        Column(Modifier.fillMaxSize()) {
                            SummaryCard(
                                totalDeliveries = totalDeliveries,
                                totalEarnings = totalEarnings,
                                totalItems = totalItems
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
                            } else if (selectedTab == 0) {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(filteredOrders, key = { it.id }) { order ->
                                        PartnerHistoryOrderCard(order = order)
                                    }
                                }
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(itemSummary, key = { it.name }) { row ->
                                        ItemSummaryCard(
                                            name = row.name,
                                            quantity = row.quantity,
                                            revenue = row.revenue
                                        )
                                    }
                                    item { Spacer(modifier = Modifier.height(8.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryCard(totalDeliveries: Int, totalEarnings: Double, totalItems: Int) {
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
                    text = "Total Items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = totalItems.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
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
                    "Your Cut: ৳${"%.2f".format(partnerEarnings)}",
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
                        "৳${"%.2f".format(item.price * item.quantity)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

/**
 * One row of the "Item Summary" tab - total quantity sold and total revenue for a single
 * item name, across every order currently matched by the search/date filters.
 */
@Composable
fun ItemSummaryCard(name: String, quantity: Int, revenue: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "$quantity sold",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            Text(
                "৳${"%.2f".format(revenue)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}