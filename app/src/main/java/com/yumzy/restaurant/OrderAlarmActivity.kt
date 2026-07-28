package com.yumzy.restaurant

import android.app.NotificationManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.yumzy.restaurant.ui.theme.YumzyRestaurantTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class OrderAlarmActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val orderId = intent.getStringExtra("ORDER_ID") ?: "Unknown"
        val customerName = intent.getStringExtra("CUSTOMER_NAME") ?: "Customer"
        val itemCount = intent.getIntExtra("ITEM_COUNT", 0)
        val orderStatus = intent.getStringExtra("ORDER_STATUS") ?: "New"
        val itemNames = intent.getStringExtra("ITEM_NAMES") ?: ""
        val userSubLocation = intent.getStringExtra("USER_SUB_LOCATION") ?: ""
        val userPhone = intent.getStringExtra("USER_PHONE") ?: ""
        val notificationId = intent.getIntExtra("NOTIFICATION_ID", -1)
        val type = intent.getStringExtra("TYPE") ?: "new"
        val isCancel = type == "cancel"

        startAlarmSound()

        setContent {
            YumzyRestaurantTheme {
                OrderAlarmScreen(
                    isCancel = isCancel,
                    orderId = orderId,
                    customerName = customerName,
                    itemCount = itemCount,
                    orderStatus = orderStatus,
                    itemNames = itemNames,
                    userSubLocation = userSubLocation,
                    userPhone = userPhone,
                    onDismiss = {
                        stopAlarmSound()

                        // FIX: this is what was missing before - dismissing the popup used to
                        // only stop the MediaPlayer, leaving the notification (and whatever
                        // sound/vibration is tied to it) alive in the tray until manually
                        // tapped. Cancelling it here means one tap fully clears the alert.
                        if (notificationId != -1) {
                            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            manager.cancel(notificationId)
                        }

                        if (isCancel) {
                            // Requirement: acknowledging a cancellation should clear any
                            // Accepted/Ready status this restaurant had already set on the
                            // order's items in Firestore, since the order no longer needs it.
                            clearPartnerStatusInFirestore(orderId)
                        }

                        finish()
                    }
                )
            }
        }
    }

    /** Removes the "partnerStatus" field from this restaurant's items on a cancelled order. */
    private fun clearPartnerStatusInFirestore(orderId: String) {
        val prefs = getSharedPreferences("YumzyPartnerPrefs", MODE_PRIVATE)
        val partnerName = prefs.getString("res_name", null)
            ?.trim()?.replace(Regex("\\s+"), " ") ?: return

        lifecycleScope.launch {
            try {
                val db = Firebase.firestore
                val orderRef = db.collection("orders").document(orderId)
                val orderDoc = orderRef.get().await()
                val items = orderDoc.get("items") as? List<Map<String, Any>> ?: return@launch

                var changed = false
                val updatedItems = items.map { item ->
                    val itemMiniResName = (item["miniResName"] as? String)
                        ?.trim()?.replace(Regex("\\s+"), " ") ?: ""
                    if (itemMiniResName.equals(partnerName, ignoreCase = true) &&
                        item.containsKey("partnerStatus")
                    ) {
                        changed = true
                        item.toMutableMap().apply { remove("partnerStatus") }
                    } else {
                        item
                    }
                }

                if (changed) {
                    orderRef.update("items", updatedItems).await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer.create(this, alarmUri).apply {
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarmSound()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent back button from dismissing without stopping alarm.
        // User must click the dismiss button.
    }
}

@Composable
fun OrderAlarmScreen(
    isCancel: Boolean,
    orderId: String,
    customerName: String,
    itemCount: Int,
    orderStatus: String,
    itemNames: String,
    userSubLocation: String,
    userPhone: String,
    onDismiss: () -> Unit
) {
    // New order = green. Cancelled order = red. Requested explicitly so the two alert types
    // are unmistakably different at a glance, especially in a dark full-screen popup.
    val accentColor = if (isCancel) Color(0xFFC62828) else Color(0xFF2E7D32)
    val containerColor = if (isCancel) Color(0xFFFDEAEA) else Color(0xFFE8F5E9)
    val onContainerColor = Color(0xFF212121)
    val icon: ImageVector = if (isCancel) Icons.Default.Cancel else Icons.Default.Notifications
    val title = if (isCancel) "ORDER CANCELLED!" else "NEW ORDER!"
    val buttonLabel = if (isCancel) "OK, CANCEL" else "GOT IT - STOP ALARM"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(80.dp),
                    tint = accentColor
                )

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    textAlign = TextAlign.Center
                )

                Divider(color = onContainerColor.copy(alpha = 0.15f))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Order #${orderId.take(6)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onContainerColor
                    )

                    Text(
                        text = customerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = onContainerColor.copy(alpha = 0.8f)
                    )

                    if (userSubLocation.isNotBlank()) {
                        Text(
                            text = userSubLocation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainerColor.copy(alpha = 0.7f)
                        )
                    }

                    if (userPhone.isNotBlank()) {
                        Text(
                            text = userPhone,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainerColor.copy(alpha = 0.7f)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "$itemCount item(s)",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = onContainerColor
                        )
                    }

                    if (itemNames.isNotBlank()) {
                        Text(
                            text = itemNames,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = onContainerColor.copy(alpha = 0.9f)
                        )
                    }

                    Text(
                        text = if (isCancel) "This order was cancelled by the customer." else "Status: $orderStatus",
                        style = MaterialTheme.typography.bodyLarge,
                        color = onContainerColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = buttonLabel,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Text(
                    text = if (isCancel) "Tap to stop alarm and acknowledge" else "Tap to stop alarm and view order",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainerColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}