package com.yumzy.restaurant.screens.manage

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.data.StoreItem
import com.yumzy.restaurant.utils.ImageUploadHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantItemListScreen(
    miniResId: String,
    subCategoryName: String,
    onBack: () -> Unit
) {
    var items by remember { mutableStateOf<List<StoreItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDialog by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<StoreItem?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    fun refreshItems() {
        isLoading = true
        coroutineScope.launch {
            try {
                val result = Firebase.firestore.collection("store_items")
                    .whereEqualTo("miniRes", miniResId)
                    .whereEqualTo("subCategory", subCategoryName)
                    .get().await()
                items = result.documents.mapNotNull { it.toObject(StoreItem::class.java)?.copy(id = it.id) }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    fun deleteItem(item: StoreItem) {
        coroutineScope.launch {
            try {
                if (item.imageUrl.isNotBlank()) {
                    ImageUploadHelper.deleteImage(item.imageUrl)
                }
                Firebase.firestore.collection("store_items").document(item.id).delete().await()
                Toast.makeText(context, "Item deleted", Toast.LENGTH_SHORT).show()
                refreshItems()
            } catch (e: Exception) {
                Toast.makeText(context, "Error deleting item", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshItems()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Items in $subCategoryName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                itemToEdit = null
                showDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Item")
            }
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items) { item ->
                    AdminStoreItemCard(
                        item = item,
                        onEditClick = {
                            itemToEdit = item
                            showDialog = true
                        },
                        onDeleteClick = {
                            deleteItem(item)
                        },
                        onStockChange = { newStockStatus ->
                            val stockValue = if (newStockStatus) "yes" else "no"
                            Firebase.firestore.collection("store_items").document(item.id)
                                .update("stock", stockValue)
                                .addOnSuccessListener { refreshItems() }
                        }
                    )
                }
            }
        }

        if (showDialog) {
            AddEditStoreItemDialog(
                item = itemToEdit,
                onDismiss = { showDialog = false },
                onSave = { itemData, imageUri ->
                    coroutineScope.launch {
                        try {
                            Toast.makeText(context, "Uploading...", Toast.LENGTH_SHORT).show()
                            val imageUrl = if (imageUri != null) {
                                if (itemToEdit != null) {
                                    ImageUploadHelper.replaceImage(
                                        context = context,
                                        oldImageUrl = itemToEdit?.imageUrl,
                                        newImageUri = imageUri,
                                        folder = "store_items",
                                        imageType = ImageUploadHelper.ImageType.STORE_ITEM
                                    )
                                } else {
                                    ImageUploadHelper.uploadImage(
                                        context = context,
                                        uri = imageUri,
                                        folder = "store_items",
                                        imageType = ImageUploadHelper.ImageType.STORE_ITEM
                                    )
                                }
                            } else {
                                itemToEdit?.imageUrl ?: ""
                            }

                            itemData["imageUrl"] = imageUrl

                            val task = if (itemToEdit == null) {
                                Firebase.firestore.collection("store_items").add(itemData)
                            } else {
                                Firebase.firestore.collection("store_items").document(itemToEdit!!.id).set(itemData)
                            }

                            task.await()
                            Toast.makeText(context, "Item Saved!", Toast.LENGTH_SHORT).show()
                            refreshItems()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    showDialog = false
                },
                onDelete = { item ->
                    deleteItem(item)
                    showDialog = false
                },
                fixedMiniResId = miniResId,
                fixedSubCategory = subCategoryName
            )
        }
    }
}

// Re-using the card from the admin app
@Composable
fun AdminStoreItemCard(
    item: StoreItem,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onStockChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxHeight().width(120.dp)
            )
            Column(
                modifier = Modifier.padding(12.dp).fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("৳${"%.2f".format(item.price)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onEditClick) { Text("EDIT") }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if(item.stock == "yes") "In Stock" else "Out", fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = item.stock == "yes",
                            onCheckedChange = onStockChange,
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// Re-using the dialog from the admin app
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditStoreItemDialog(
    item: StoreItem?,
    onDismiss: () -> Unit,
    onSave: (HashMap<String, Any>, Uri?) -> Unit,
    onDelete: ((StoreItem) -> Unit)? = null,
    fixedMiniResId: String,
    fixedSubCategory: String
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var price by remember { mutableStateOf(item?.price?.toString() ?: "") }
    var description by remember { mutableStateOf(item?.itemDescription ?: "") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }

    // Multi-variant fields
    var hasMultiVariant by remember { mutableStateOf(false) }
    var variantCount by remember { mutableStateOf("2") }
    var variantNames by remember { mutableStateOf(listOf("", "")) }
    var variantPrices by remember { mutableStateOf(listOf("", "")) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        selectedImageUri = uri
    }

    // Load existing multi-variant data if editing
    LaunchedEffect(item) {
        if (item != null) {
            try {
                val doc = Firebase.firestore.collection("store_items").document(item.id).get().await()
                val multiVariantValue = doc.getLong("multiVariant")?.toInt() ?: 0

                if (multiVariantValue >= 2) {
                    hasMultiVariant = true
                    variantCount = multiVariantValue.toString()

                    val names = mutableListOf<String>()
                    val prices = mutableListOf<String>()

                    for (i in 1..multiVariantValue) {
                        names.add(doc.getString("variant${i}name") ?: "")
                        prices.add(doc.getDouble("variant${i}price")?.toString() ?: "")
                    }

                    variantNames = names
                    variantPrices = prices
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (item == null) "Add Item" else "Edit Item", style = MaterialTheme.typography.titleLarge)

                    TextField(value = name, onValueChange = { name = it }, label = { Text("Item Name") })

                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clickable { imagePickerLauncher.launch("image/*") },
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            when {
                                selectedImageUri != null -> {
                                    AsyncImage(
                                        model = selectedImageUri,
                                        contentDescription = "Selected image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                item?.imageUrl?.isNotBlank() == true -> {
                                    AsyncImage(
                                        model = item.imageUrl,
                                        contentDescription = "Current image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                else -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.AddPhotoAlternate,
                                            contentDescription = "Select image",
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Tap to select image", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = "Upload",
                                    modifier = Modifier.padding(8.dp).size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = hasMultiVariant,
                            onCheckedChange = {
                                hasMultiVariant = it
                                if (it && variantCount.toIntOrNull() ?: 0 < 2) {
                                    variantCount = "2"
                                    variantNames = listOf("", "")
                                    variantPrices = listOf("", "")
                                }
                            }
                        )
                        Text("Multi-Variant")
                    }

                    if (hasMultiVariant) {
                        TextField(
                            value = variantCount,
                            onValueChange = {
                                val count = it.toIntOrNull() ?: 2
                                if (count >= 2 && count <= 10) {
                                    variantCount = it
                                    val currentNames = variantNames.toMutableList()
                                    val currentPrices = variantPrices.toMutableList()
                                    while (currentNames.size < count) {
                                        currentNames.add("")
                                        currentPrices.add("")
                                    }
                                    while (currentNames.size > count) {
                                        currentNames.removeAt(currentNames.lastIndex)
                                        currentPrices.removeAt(currentPrices.lastIndex)
                                    }
                                    variantNames = currentNames
                                    variantPrices = currentPrices
                                }
                            },
                            label = { Text("Number of Variants (2-10)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        Text(
                            "Variant Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        val count = variantCount.toIntOrNull() ?: 2
                        for (i in 0 until count.coerceAtMost(variantNames.size)) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Variant ${i + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    TextField(
                                        value = variantNames.getOrElse(i) { "" },
                                        onValueChange = { newValue ->
                                            val updated = variantNames.toMutableList()
                                            if (i < updated.size) {
                                                updated[i] = newValue
                                            }
                                            variantNames = updated
                                        },
                                        label = { Text("Variant Name") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    TextField(
                                        value = variantPrices.getOrElse(i) { "" },
                                        onValueChange = { newValue ->
                                            val updated = variantPrices.toMutableList()
                                            if (i < updated.size) {
                                                updated[i] = newValue
                                            }
                                            variantPrices = updated
                                        },
                                        label = { Text("Price (৳)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    } else {
                        TextField(
                            value = price,
                            onValueChange = { price = it },
                            label = { Text("Price (৳)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    TextField(value = description, onValueChange = { description = it }, label = { Text("Description (Optional)") }, maxLines = 3)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (item != null && onDelete != null) {
                            var showDeleteConfirm by remember { mutableStateOf(false) }
                            TextButton(
                                onClick = { showDeleteConfirm = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete Item")
                            }
                            if (showDeleteConfirm) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteConfirm = false },
                                    title = { Text("Delete Item?") },
                                    text = { Text("Are you sure you want to delete '${item.name}'?") },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                onDelete(item)
                                                showDeleteConfirm = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Delete")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showDeleteConfirm = false }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Row {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Button(onClick = {
                                if (name.isBlank()) {
                                    Toast.makeText(context, "Item name is required.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (item == null && selectedImageUri == null) {
                                    Toast.makeText(context, "Please select an image.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                val newItemData = hashMapOf<String, Any>(
                                    "name" to name,
                                    "itemDescription" to description,
                                    "miniRes" to fixedMiniResId,
                                    "subCategory" to fixedSubCategory,
                                    "stock" to (item?.stock ?: "yes")
                                )

                                if (hasMultiVariant) {
                                    val count = variantCount.toIntOrNull() ?: 2
                                    if (count < 2) {
                                        Toast.makeText(context, "Must have at least 2 variants.", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    for (i in 0 until count) {
                                        if (variantNames.getOrNull(i).isNullOrBlank() || variantPrices.getOrNull(i).isNullOrBlank()) {
                                            Toast.makeText(context, "All variants must have name and price.", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                    }

                                    newItemData["multiVariant"] = count
                                    newItemData["price"] = 0.0

                                    for (i in 0 until count) {
                                        val variantPrice = variantPrices[i].toDoubleOrNull()
                                        if (variantPrice == null) {
                                            Toast.makeText(context, "Invalid price for variant ${i + 1}.", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        newItemData["variant${i + 1}name"] = variantNames[i]
                                        newItemData["variant${i + 1}price"] = variantPrice
                                    }
                                } else {
                                    val priceDouble = price.toDoubleOrNull()
                                    if (priceDouble == null) {
                                        Toast.makeText(context, "Valid price is required.", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    newItemData["price"] = priceDouble
                                    newItemData["multiVariant"] = 0
                                }

                                onSave(newItemData, selectedImageUri)
                            }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }
}