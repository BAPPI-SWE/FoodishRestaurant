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
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.MainActivity
import com.yumzy.restaurant.OrderAlarmActivity
import com.yumzy.restaurant.R
import com.yumzy.restaurant.utils.OrderAlertTracker

class OrderMonitorService : Service() {

    private var firestoreListener: ListenerRegistration? = null
    private val CHANNEL_ID_FOREGROUND = "yumzy_restaurant_service"
    private val CHANNEL_ID_ALERTS = "yumzy_restaurant_alerts"
    private val CHANNEL_ID_CANCEL_ALERTS = "yumzy_restaurant_cancel_alerts"
    private val NOTIFICATION_ID_SERVICE = 1
    private val PREFS_NAME = "YumzyPartnerPrefs"

    // All order statuses this service needs to watch for this partner.
    private val WATCHED_STATUSES = listOf("Pending", "Accepted", "Preparing", "On the way", "Cancelled")

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID_SERVICE, createForegroundNotification())
        OrderAlertTracker.purgeExpiredCancelMeta(this)
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

        val partnerName = restaurantName.trim()
        val db = Firebase.firestore

        // FIX: added orderBy + limit. Firestore doesn't guarantee document order without an
        // orderBy clause, and the previous unbounded query could theoretically miss/starve
        // out recent documents as the collection grows. Ordering by createdAt + a sane limit
        // keeps this bounded to the most recent, relevant orders.
        firestoreListener = db.collection("orders")
            .whereIn("orderStatus", WATCHED_STATUSES)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e("OrderService", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    for (dc in snapshots.documentChanges) {
                        val allItems = dc.document.get("items") as? List<Map<String, Any>> ?: emptyList()

                        val partnerItems = allItems.filter { itemMap ->
                            val itemMiniResName = (itemMap["miniResName"] as? String)?.trim() ?: ""
                            itemMiniResName.equals(partnerName, ignoreCase = true)
                        }

                        if (partnerItems.isEmpty()) continue

                        val orderId = dc.document.id
                        val customerName = dc.document.getString("userName") ?: "Customer"
                        val orderStatus = dc.document.getString("orderStatus") ?: "New"
                        val userSubLocation = dc.document.getString("userSubLocation") ?: ""
                        val userPhone = dc.document.getString("userPhone") ?: ""
                        val itemCount = partnerItems.sumOf { (it["quantity"] as? Number)?.toInt() ?: 0 }
                        val itemNames = partnerItems.joinToString(", ") { item ->
                            val qty = (item["quantity"] as? Number)?.toInt() ?: 0
                            val name = item["itemName"] as? String ?: "Unknown"
                            "${qty}x $name"
                        }

                        if (orderStatus == "Cancelled") {
                            // FIX: dedup so the same order never re-triggers the alarm, and so
                            // the alarm actually fires once (previously "Cancelled" wasn't
                            // watched by this service at all, so restaurants never got any
                            // alert when a customer cancelled).
                            if (OrderAlertTracker.hasAlertedCancelledOrder(this, orderId)) continue

                            val hadProgress = partnerItems.any { item ->
                                val partnerStatus = item["partnerStatus"] as? String
                                partnerStatus == "Accepted" || partnerStatus == "Ready"
                            }
                            OrderAlertTracker.recordCancelMeta(this, orderId, hadProgress)
                            OrderAlertTracker.markCancelledOrderAlerted(this, orderId)

                            triggerAlarmAndNotification(
                                type = "cancel",
                                orderId = orderId,
                                customerName = customerName,
                                orderStatus = orderStatus,
                                itemCount = itemCount,
                                itemNames = itemNames,
                                userSubLocation = userSubLocation,
                                userPhone = userPhone
                            )
                        } else if (dc.type == DocumentChange.Type.ADDED) {
                            // FIX: Firestore replays the *entire initial snapshot* as ADDED
                            // events. Without this dedup check, every service restart used to
                            // re-alarm for every currently active order at once, flooding the
                            // partner with alarms and effectively burying the next real one.
                            if (OrderAlertTracker.hasAlertedNewOrder(this, orderId)) continue
                            OrderAlertTracker.markNewOrderAlerted(this, orderId)

                            triggerAlarmAndNotification(
                                type = "new",
                                orderId = orderId,
                                customerName = customerName,
                                orderStatus = orderStatus,
                                itemCount = itemCount,
                                itemNames = itemNames,
                                userSubLocation = userSubLocation,
                                userPhone = userPhone
                            )
                        }
                    }
                }
            }
    }

    private fun triggerAlarmAndNotification(
        type: String, // "new" or "cancel"
        orderId: String,
        customerName: String,
        orderStatus: String,
        itemCount: Int,
        itemNames: String,
        userSubLocation: String,
        userPhone: String
    ) {
        val isCancel = type == "cancel"
        val channelId = if (isCancel) CHANNEL_ID_CANCEL_ALERTS else CHANNEL_ID_ALERTS

        // Launch full-screen alarm activity
        val alarmIntent = Intent(this, OrderAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("TYPE", type)
            putExtra("ORDER_ID", orderId)
            putExtra("CUSTOMER_NAME", customerName)
            putExtra("ORDER_STATUS", orderStatus)
            putExtra("ITEM_COUNT", itemCount)
            putExtra("ITEM_NAMES", itemNames)
            putExtra("USER_SUB_LOCATION", userSubLocation)
            putExtra("USER_PHONE", userPhone)
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

        val title = if (isCancel) "Order Cancelled!" else "New Order Received!"
        val shortText = if (isCancel) {
            "$itemCount item(s) from $customerName was cancelled"
        } else {
            "$itemCount item(s) from $customerName - Status: $orderStatus"
        }
        val bigText = if (isCancel) {
            "Order #${orderId.take(6)}\n$itemNames\nCancelled by $customerName"
        } else {
            "Order #${orderId.take(6)}\n$itemCount item(s) from $customerName\nStatus: $orderStatus"
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
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

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Loud Channel for New Orders with HIGH importance
            val newOrderSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "New Order Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new incoming orders"
                enableVibration(true)
                setSound(newOrderSoundUri, audioAttributes)
            }
            manager.createNotificationChannel(alertChannel)

            // Loud channel dedicated to cancellations, so partners can tell them apart in
            // system settings if they ever want to customize either independently.
            val cancelChannel = NotificationChannel(
                CHANNEL_ID_CANCEL_ALERTS,
                "Cancelled Order Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for orders cancelled by customers"
                enableVibration(true)
                setSound(newOrderSoundUri, audioAttributes)
            }
            manager.createNotificationChannel(cancelChannel)
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