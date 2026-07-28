package com.yumzy.restaurant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.MainActivity
import com.yumzy.restaurant.OrderAlarmActivity
import com.yumzy.restaurant.R
import com.yumzy.restaurant.utils.OrderAlertTracker

/**
 * IMPORTANT: This service is the ONLY place in the app that triggers order alarms (sound +
 * full-screen activity + notification). LiveOrdersScreen only *displays* data - it must not
 * also alarm. Two independent listeners (screen + service) both trying to alarm off the same
 * Firestore events was the root cause of the "two sounds playing" bug: both would pass the
 * dedup check at almost the same instant before either had written the "already alerted" flag,
 * so both fired. A single dispatcher removes that race entirely.
 */
class OrderMonitorService : Service() {

    private var firestoreListener: ListenerRegistration? = null
    private val CHANNEL_ID_FOREGROUND = "yumzy_restaurant_service"
    private val CHANNEL_ID_ALERTS = "yumzy_restaurant_alerts"
    private val CHANNEL_ID_CANCEL_ALERTS = "yumzy_restaurant_cancel_alerts"
    private val NOTIFICATION_ID_SERVICE = 1
    private val PREFS_NAME = "YumzyPartnerPrefs"

    private val WATCHED_STATUSES = listOf("Pending", "Accepted", "Preparing", "On the way", "Cancelled")

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID_SERVICE, createForegroundNotification())
        OrderAlertTracker.purgeExpiredFallbackTimestamps(this)
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

        val partnerName = restaurantName.trim().replace(Regex("\\s+"), " ")
        val db = Firebase.firestore

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
                            val itemMiniResName = (itemMap["miniResName"] as? String)
                                ?.trim()?.replace(Regex("\\s+"), " ") ?: ""
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
                            // Stamp a real, synced Firestore timestamp the first time ANY device
                            // observes this cancellation, so the 10-minute "show cancelled order"
                            // countdown is consistent everywhere instead of relying on local,
                            // per-device guesses. Safe to call every time - it's a no-op once set.
                            if (dc.document.get("cancelledObservedAt") == null) {
                                dc.document.reference.update(
                                    "cancelledObservedAt", FieldValue.serverTimestamp()
                                ).addOnFailureListener {
                                    Log.e("OrderService", "Failed to stamp cancelledObservedAt", it)
                                }
                            }

                            if (OrderAlertTracker.hasAlertedCancelledOrder(this, orderId)) continue
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
                            // FIX: Firestore replays the entire initial snapshot as ADDED events
                            // on every listener restart. Without this dedup check, every service
                            // restart used to re-alarm for every currently active order at once.
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

        // Stable, deterministic ID (instead of the old System.currentTimeMillis().toInt()) so
        // the alarm Activity can look this exact notification back up and cancel it once the
        // partner dismisses the popup - that's what fixes "I have to click the notification too".
        val notificationId = ("order_$orderId").hashCode()

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
            putExtra("NOTIFICATION_ID", notificationId)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            alarmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

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
            // FIX: no .setSound() here anymore. The channel itself is silent (see
            // createNotificationChannels) - the full-screen OrderAlarmActivity's own looping
            // MediaPlayer is the single audible alarm now, instead of two sounds layering.
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(true) // stays until we explicitly cancel it from the alarm activity
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)

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

            val serviceChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "Yumzy Restaurant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the app monitoring for orders"
            }
            manager.createNotificationChannel(serviceChannel)

            // FIX: sound set to null on both alert channels. The full-screen OrderAlarmActivity
            // plays the actual audible alarm via MediaPlayer; the notification's only jobs now
            // are the full-screen-intent trigger, the heads-up visual, and vibration. Having the
            // channel ALSO play a sound was the direct cause of "2 sounds playing at once".
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERTS,
                "New Order Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new incoming orders"
                enableVibration(true)
                setSound(null, null)
            }
            manager.createNotificationChannel(alertChannel)

            val cancelChannel = NotificationChannel(
                CHANNEL_ID_CANCEL_ALERTS,
                "Cancelled Order Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for orders cancelled by customers"
                enableVibration(true)
                setSound(null, null)
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