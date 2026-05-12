package com.yumzy.restaurant.data

import com.google.firebase.Timestamp

/**
 * Represents a MiniRestaurant.
 * Added 'password' field for login.
 * --- UPDATED: Made fields nullable to prevent loading errors ---
 */
data class MiniRestaurant(
    val id: String = "",
    val name: String? = null,
    val imageUrl: String? = null,
    val open: String? = "yes",
    val parentCategory: String? = null,
    val availableLocations: List<String>? = emptyList(),
    val password: String? = null
)

/**
 * Represents a sub-category for a store.
 * (Copied from Admin app)
 */
data class StoreSubCategory(
    val id: String = "",
    val name: String = "",
    val parentCategory: String = "",
    val imageUrl: String? = null,
    val availableLocations: List<String> = emptyList()
)

/**
 * Represents an item in a store.
 * --- UPDATED: Removed additionalDeliveryCharge and additionalServiceCharge ---
 */
data class StoreItem(
    val id: String = "",
    val name: String = "",
    val price: Double = 0.0,
    val imageUrl: String = "",
    val itemDescription: String = "",
    val subCategory: String = "",
    val miniRes: String = "", // The ID of the mini restaurant
    val stock: String = "yes" // "yes" or "no"
)

/**
 * Represents an item inside an order, with details.
 * (Copied from Admin app)
 */
data class OrderItemDetail(
    val name: String = "",
    val quantity: Int = 0,
    val price: Double = 0.0,
    val miniResName: String = "", // We need this to filter!
    val partnerStatus: String? = null  // ADD THIS
)

/**
 * A custom Order class for the partner.
 * It will only contain items relevant to this partner.
 * --- UPDATED: Added userPhone field ---
 */
data class PartnerOrder(
    val id: String = "",
    val orderStatus: String = "",
    val userName: String = "",
    val userSubLocation: String = "",
    val userPhone: String = "", // Added this field
    val totalPrice: Double = 0.0, // This is the TOTAL order price
    val createdAt: Timestamp = Timestamp.now(),
    val items: List<OrderItemDetail> = emptyList() // This list is FILTERED
)