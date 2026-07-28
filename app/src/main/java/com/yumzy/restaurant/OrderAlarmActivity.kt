package com.yumzy.restaurant

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/**
 * Shows a full-screen, alarm-style popup for a new order or a cancellation.
 *
 * IMPORTANT: This activity must be declared with android:launchMode="singleTask" in
 * AndroidManifest.xml. Combined with FLAG_ACTIVITY_SINGLE_TOP (set on the intent by
 * OrderMonitorService), that guarantees at most one instance of this activity - and therefore
 * at most one MediaPlayer alarm loop - ever exists. If a second alert arrives while this screen
 * is already showing, Android reuses this instance and delivers the new order via onNewIntent()
 * instead of stacking a second activity (and a second alarm sound) on top of it.
 */
class OrderAlarmActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    // Holds the currently displayed order's details. Re-populated both in onCreate() and in
    // onNewIntent() so a reused instance always reflects the latest alert.
    private var uiState = mutableStateOf(AlarmUiState())

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

        applyIntent(intent)
        startAlarmSound()

        setContent {
            YumzyRestaurantTheme {
                val state by uiState
                OrderAlarmScreen(
                    state = state,
                    onDismiss = { handleDismiss(state) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Stop whatever was playing for the previous alert before starting the new one - this
        // is the guard that actually prevents two overlapping alarm sounds if a second alert
        // lands while the popup is still on screen.
        stopAlarmSound()
        applyIntent(intent)
        startAlarmSound()
    }

    private fun applyIntent(intent: Intent) {
        uiState.value = AlarmUiState(
            type = intent.getStringExtra("TYPE") ?: "new",
            orderId = intent.getStringExtra("ORDER_ID") ?: "Unknown",
            partnerName = intent.getStringExtra("PARTNER_NAME") ?: "",
            customerName = intent.getStringExtra("CUSTOMER_NAME") ?: "Customer",
            itemCount = intent.getIntExtra("ITEM_COUNT", 0),
            orderStatus = intent.getStringExtra("ORDER_STATUS") ?: "New",
            itemNames = intent.getStringExtra("ITEM_NAMES") ?: "",
            userSubLocation = intent.getStringExtra("USER_SUB_LOCATION") ?: "",
            userPhone = intent.getStringExtra("USER_PHONE") ?: "",
            notificationId = intent.getIntExtra("NOTIFICATION_ID", -1)
        )
    }

    private fun handleDismiss(state: AlarmUiState) {
        stopAlarmSound()

        // Cancel the notification that spawned this alarm - it's ongoing (setOngoing(true)) so
        // it otherwise stays in the shade forever, which was why the partner had to separately
        // tap the notification to make the second sound stop.
        if (state.notificationId != -1) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(state.notificationId)
        }

        if (state.type == "cancel") {
            clearPartnerStatusOnCancel(state.orderId, state.partnerName)
        }

        finish()
    }

    /**
     * When a customer cancels an order, reset this partner's items back to a clean state by
     * removing the "partnerStatus" field entirely (rather than leaving a stale "Accepted" /
     * "Ready" value behind on a cancelled order).
     */
    private fun clearPartnerStatusOnCancel(orderId: String, partnerName: String) {
        if (orderId.isBlank() || orderId == "Unknown" || partnerName.isBlank()) return

        lifecycleScope.launch {
            try {
                val db = Firebase.firestore
                val orderRef = db.collection("orders").document(orderId)

                // Same "items" array race as the Accept/Ready buttons: other partners on this
                // order, or a status update in flight from LiveOrdersScreen, could write to
                // this array at the same moment. A transaction re-reads the latest server data
                // and retries on conflict instead of blindly overwriting with a stale copy.
                db.runTransaction { transaction ->
                    val doc = transaction.get(orderRef)

                    @Suppress("UNCHECKED_CAST")
                    val items = doc.get("items") as? List<Map<String, Any>> ?: return@runTransaction

                    var changed = false
                    val updatedItems = items.map { item ->
                        val itemMiniResName = (item["miniResName"] as? String)?.trim() ?: ""
                        if (itemMiniResName.equals(partnerName.trim(), ignoreCase = true) &&
                            item.containsKey("partnerStatus")
                        ) {
                            changed = true
                            item.toMutableMap().apply { remove("partnerStatus") }
                        } else {
                            item
                        }
                    }

                    if (changed) {
                        transaction.update(orderRef, "items", updatedItems)
                    }
                }.await()
            } catch (e: Exception) {
                Log.e("OrderAlarmActivity", "Failed to clear partnerStatus after cancel", e)
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
            try {
                if (isPlaying) stop()
            } catch (_: IllegalStateException) {
                // already stopped/released - nothing to do
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
        // Prevent back button from dismissing without stopping the alarm.
        // User must use the dismiss button.
    }
}

data class AlarmUiState(
    val type: String = "new",
    val orderId: String = "Unknown",
    val partnerName: String = "",
    val customerName: String = "Customer",
    val itemCount: Int = 0,
    val orderStatus: String = "New",
    val itemNames: String = "",
    val userSubLocation: String = "",
    val userPhone: String = "",
    val notificationId: Int = -1
)

@Composable
fun OrderAlarmScreen(
    state: AlarmUiState,
    onDismiss: () -> Unit
) {
    val isCancel = state.type == "cancel"

    // New order = green, cancelled order = red - makes the two alert types instantly
    // distinguishable at a glance, especially useful since both use the same full-screen layout.
    val accentColor = if (isCancel) Color(0xFFC62828) else Color(0xFF2E7D32)
    val containerColor = if (isCancel) Color(0xFFFDECEA) else Color(0xFFE8F5E9)
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

                Divider(color = accentColor.copy(alpha = 0.3f))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Order #${state.orderId.take(6)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = state.customerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.DarkGray
                    )

                    if (state.userSubLocation.isNotBlank()) {
                        Text(
                            text = state.userSubLocation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }

                    if (state.userPhone.isNotBlank()) {
                        Text(
                            text = state.userPhone,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${state.itemCount} item(s)",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }

                    if (state.itemNames.isNotBlank()) {
                        Text(
                            text = state.itemNames,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = Color.DarkGray
                        )
                    }

                    Text(
                        text = if (isCancel) "This order was cancelled by the customer." else "Status: ${state.orderStatus}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray,
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
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}