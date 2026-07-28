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

    // THIS SERVICE IS THE ONLY PLACE THAT TRIGGERS ALARMS / NOTIFICATIONS.
    // LiveOrdersScreen used to run its own, separate Firestore listener that *also* fired
    // alarms. Since the service is always running whenever the partner is logged in (started
    // in MainActivity, stopped only on sign out), having both listeners race to fire the alarm
    // for the same order was the root cause of "2 sounds playing" / the alarm not stopping
    // properly - both listeners could pass the "have I alerted this order yet?" check before
    // either had a chance to mark it, so both launched their own OrderAlarmActivity + their own
    // MediaPlayer loop. LiveOrdersScreen now only displays data.

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

        val partnerName = restaurantName.trim()
        val db = Firebase.firestore

        // orderBy + limit: Firestore doesn't guarantee document order without an orderBy
        // clause, and an unbounded query could theoretically miss/starve out recent documents
        // as the collection grows. Ordering by createdAt + a sane limit keeps this bounded to
        // the most recent, relevant orders.
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
                            // Dedup so the same order never re-triggers the alarm.
                            if (OrderAlertTracker.hasAlertedCancelledOrder(this, orderId)) continue
                            OrderAlertTracker.markCancelledOrderAlerted(this, orderId)

                            triggerAlarmAndNotification(
                                type = "cancel",
                                orderId = orderId,
                                partnerName = partnerName,
                                customerName = customerName,
                                orderStatus = orderStatus,
                                itemCount = itemCount,
                                itemNames = itemNames,
                                userSubLocation = userSubLocation,
                                userPhone = userPhone
                            )
                        } else if (dc.type == DocumentChange.Type.ADDED) {
                            // Firestore replays the *entire initial snapshot* as ADDED events.
                            // Without this dedup check, every service restart would re-alarm
                            // for every currently active order at once.
                            if (OrderAlertTracker.hasAlertedNewOrder(this, orderId)) continue
                            OrderAlertTracker.markNewOrderAlerted(this, orderId)

                            triggerAlarmAndNotification(
                                type = "new",
                                orderId = orderId,
                                partnerName = partnerName,
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
        partnerName: String,
        customerName: String,
        orderStatus: String,
        itemCount: Int,
        itemNames: String,
        userSubLocation: String,
        userPhone: String
    ) {
        val isCancel = type == "cancel"
        val channelId = if (isCancel) CHANNEL_ID_CANCEL_ALERTS else CHANNEL_ID_ALERTS

        // Stable ID per order+type, instead of System.currentTimeMillis().toInt(). This means a
        // repeat delivery for the same order replaces the existing notification instead of
        // stacking a new one on top of it, and it lets OrderAlarmActivity cancel the *exact*
        // notification that spawned it when the partner dismisses the alarm.
        val notificationId = (orderId + type).hashCode()

        val alarmIntent = Intent(this, OrderAlarmActivity::class.java).apply {
            // FLAG_ACTIVITY_SINGLE_TOP (paired with android:launchMode="singleTask" on
            // OrderAlarmActivity in the manifest) means if an alarm is already showing, a new
            // one for a different order reuses that same instance via onNewIntent() instead of
            // stacking a second Activity + a second MediaPlayer on top of it.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("TYPE", type)
            putExtra("ORDER_ID", orderId)
            putExtra("PARTNER_NAME", partnerName)
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
            // setFullScreenIntent covers the locked/screen-off case (Android launches it
            // automatically then). It does NOT auto-launch while the screen is on and unlocked -
            // in that case Android only shows this as a heads-up notification, which is why the
            // popup wasn't appearing while the screen was on. We still keep this for the locked
            // case, and additionally call startActivity() below for the screen-on case.
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true) // stays until explicitly cancelled from OrderAlarmActivity's dismiss button
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)

        // Directly launch the popup so it reliably shows even when the screen is already on and
        // unlocked (the case setFullScreenIntent doesn't cover on its own). This used to cause a
        // double-launch alongside the full-screen intent, but OrderAlarmActivity's
        // singleTask launch mode + FLAG_ACTIVITY_SINGLE_TOP + onNewIntent() handling now make a
        // second launch reuse the same instance (and safely restart the sound) instead of
        // stacking a second activity and a second MediaPlayer.
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