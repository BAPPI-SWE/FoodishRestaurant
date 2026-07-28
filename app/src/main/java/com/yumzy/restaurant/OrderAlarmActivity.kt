package com.yumzy.restaurant

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
import com.yumzy.restaurant.ui.theme.YumzyRestaurantTheme
import com.yumzy.restaurant.utils.OrderAlertTracker

class OrderAlarmActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make it show over lock screen
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

        // Get order details from intent
        val orderId = intent.getStringExtra("ORDER_ID") ?: "Unknown"
        val customerName = intent.getStringExtra("CUSTOMER_NAME") ?: "Customer"
        val itemCount = intent.getIntExtra("ITEM_COUNT", 0)
        val orderStatus = intent.getStringExtra("ORDER_STATUS") ?: "New"
        val itemNames = intent.getStringExtra("ITEM_NAMES") ?: ""
        val userSubLocation = intent.getStringExtra("USER_SUB_LOCATION") ?: ""
        val userPhone = intent.getStringExtra("USER_PHONE") ?: ""
        // "new" -> brand-new order alert. "cancel" -> customer cancelled an order.
        val type = intent.getStringExtra("TYPE") ?: "new"
        val isCancel = type == "cancel"

        // Start playing alarm sound
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
                        if (isCancel) {
                            // "Undo" rule: if the partner had NOT yet marked this order
                            // Accepted/Ready when it got cancelled, there's nothing to keep a
                            // record of - clear it immediately so it never lingers in the
                            // Live Orders list. If they HAD already progressed it, the meta
                            // (and hadProgress flag) recorded by the listener is left as-is,
                            // so it stays visible for the 10 minute grace period.
                            val meta = OrderAlertTracker.getCancelMeta(this, orderId)
                            if (meta != null && !meta.hadProgress) {
                                OrderAlertTracker.clearCancelMeta(this, orderId)
                            }
                        }
                        finish()
                    }
                )
            }
        }
    }

    private fun startAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer.create(this, alarmUri).apply {
                isLooping = true // Loop the sound
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
        // Prevent back button from dismissing without stopping alarm
        // User must click the dismiss button
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
    val accentColor = if (isCancel) Color(0xFFB71C1C) else MaterialTheme.colorScheme.error
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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
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
                // Icon
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier.size(80.dp),
                    tint = accentColor
                )

                // Title
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    textAlign = TextAlign.Center
                )

                Divider(color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.3f))

                // Order Details
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Order #${orderId.take(6)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = customerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )

                    if (userSubLocation.isNotBlank()) {
                        Text(
                            text = userSubLocation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }

                    if (userPhone.isNotBlank()) {
                        Text(
                            text = userPhone,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accentColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "$itemCount item(s)",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (itemNames.isNotBlank()) {
                        Text(
                            text = itemNames,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                        )
                    }

                    Text(
                        text = if (isCancel) "This order was cancelled by the customer." else "Status: $orderStatus",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Dismiss Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = buttonLabel,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (isCancel) "Tap to stop alarm and acknowledge" else "Tap to stop alarm and view order",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}