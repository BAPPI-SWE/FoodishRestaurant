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
import com.yumzy.restaurant.OrderAlarmActivity
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
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val restaurantName = sharedPrefs.getString("res_name", null)

        if (restaurantName.isNullOrBlank()) {
            Log.e("OrderService", "No restaurant logged in")
            stopSelf()
            return
        }

        val db = Firebase.firestore

        firestoreListener = db.collection("orders")
            .whereIn("orderStatus", listOf("Pending", "Accepted", "Preparing", "On the way"))
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("OrderService", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    for (dc in snapshots.documentChanges) {
                        if (dc.type == DocumentChange.Type.ADDED) {
                            val allItems = dc.document.get("items") as? List<Map<String, Any>> ?: emptyList()

                            val hasRestaurantItems = allItems.any { itemMap ->
                                val itemMiniResName = itemMap["miniResName"] as? String ?: ""
                                itemMiniResName.trim().equals(restaurantName.trim(), ignoreCase = true)
                            }

                            if (hasRestaurantItems) {
                                val orderId = dc.document.id
                                val customerName = dc.document.getString("userName") ?: "Customer"
                                val orderStatus = dc.document.getString("orderStatus") ?: "New"

                                val itemCount = allItems.count { itemMap ->
                                    val itemMiniResName = itemMap["miniResName"] as? String ?: ""
                                    itemMiniResName.trim().equals(restaurantName.trim(), ignoreCase = true)
                                }

                                triggerAlarmAndNotification(orderId, customerName, orderStatus, itemCount)
                            }
                        }
                    }
                }
            }
    }

    private fun triggerAlarmAndNotification(
        orderId: String,
        customerName: String,
        orderStatus: String,
        itemCount: Int
    ) {
        // Launch full-screen alarm activity
        val alarmIntent = Intent(this, OrderAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ORDER_ID", orderId)
            putExtra("CUSTOMER_NAME", customerName)
            putExtra("ORDER_STATUS", orderStatus)
            putExtra("ITEM_COUNT", itemCount)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            alarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Also create a regular notification as backup
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

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
            .setFullScreenIntent(fullScreenPendingIntent, true) // This triggers the full-screen alarm
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)

        // Launch the alarm activity directly
        startActivity(alarmIntent)
    }

    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle("Foodish Restaurant Active")
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

            // Loud Channel for New Orders with HIGH importance
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
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