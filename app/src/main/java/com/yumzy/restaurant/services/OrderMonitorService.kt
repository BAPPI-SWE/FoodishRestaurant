package com.yumzy.restaurant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.MainActivity
import com.yumzy.restaurant.R

class OrderMonitorService : Service() {

    private var firestoreListener: ListenerRegistration? = null
    private val CHANNEL_ID_FOREGROUND = "yumzy_restaurant_service"
    private val CHANNEL_ID_ALERTS = "yumzy_restaurant_alerts"
    private val NOTIFICATION_ID_SERVICE = 1
    private val PREFS_NAME = "YumzyPartnerPrefs"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID_SERVICE, createForegroundNotification())
        startFirestoreListener()
    }

    private fun startFirestoreListener() {
        // Get the logged-in restaurant name from SharedPreferences
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val restaurantName = sharedPrefs.getString("res_name", null)

        if (restaurantName.isNullOrBlank()) {
            Log.e("OrderService", "No restaurant logged in")
            stopSelf()
            return
        }

        val db = Firebase.firestore

        // Listen to orders that contain items for this restaurant
        firestoreListener = db.collection("orders")
            .whereIn("orderStatus", listOf("Pending", "Accepted", "Preparing", "On the way"))
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("OrderService", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    for (dc in snapshots.documentChanges) {
                        // ONLY trigger for NEW documents added while listening
                        if (dc.type == DocumentChange.Type.ADDED) {
                            val allItems = dc.document.get("items") as? List<Map<String, Any>> ?: emptyList()

                            // Check if any item belongs to this restaurant
                            val hasRestaurantItems = allItems.any { itemMap ->
                                val itemMiniResName = itemMap["miniResName"] as? String ?: ""
                                itemMiniResName.trim().equals(restaurantName.trim(), ignoreCase = true)
                            }

                            if (hasRestaurantItems) {
                                val orderId = dc.document.id
                                val customerName = dc.document.getString("userName") ?: "Customer"
                                val orderStatus = dc.document.getString("orderStatus") ?: "New"

                                // Count items for this restaurant
                                val itemCount = allItems.count { itemMap ->
                                    val itemMiniResName = itemMap["miniResName"] as? String ?: ""
                                    itemMiniResName.trim().equals(restaurantName.trim(), ignoreCase = true)
                                }

                                triggerSoundAndNotification(orderId, customerName, orderStatus, itemCount)
                            }
                        }
                    }
                }
            }
    }

    private fun triggerSoundAndNotification(
        orderId: String,
        customerName: String,
        orderStatus: String,
        itemCount: Int
    ) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("New Order Received!")
            .setContentText("$itemCount item(s) from $customerName - Status: $orderStatus")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Order #${orderId.take(6)}\n$itemCount item(s) from $customerName\nStatus: $orderStatus")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(soundUri)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle("Yumzy Restaurant Active")
            .setContentText("Monitoring for new orders...")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Silent Channel for Background Service
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "Yumzy Restaurant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the app monitoring for orders"
            }
            manager.createNotificationChannel(serviceChannel)

            // Loud Channel for New Orders
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "New Order Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new incoming orders"
                enableVibration(true)
                setSound(soundUri, audioAttributes)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        firestoreListener?.remove()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}