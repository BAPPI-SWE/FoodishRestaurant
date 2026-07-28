package com.yumzy.restaurant.utils

import android.content.Context

/**
 * Lightweight, persistent tracker used by both OrderMonitorService (background) and
 * LiveOrdersScreen (foreground) so the two never fight or duplicate work.
 *
 * It solves two concrete bugs:
 *
 * 1) THE "ALARM FLOOD" BUG
 *    Firestore's addSnapshotListener delivers the *entire initial snapshot* as a stream of
 *    DocumentChange.Type.ADDED events - not just genuinely new documents. The old code
 *    treated every ADDED event as "new order -> ring alarm", so every time the service
 *    restarted (Android killing/recreating it, phone reboot, app relaunch, etc.) it would
 *    replay a full-screen alarm for EVERY currently active order all at once. That flood is
 *    almost certainly why "new orders sometimes don't show properly" - overlapping alarm
 *    activities / a stuck MediaPlayer from a previous instance can swallow the next genuine
 *    alert. This tracker makes sure each order id only ever triggers one "new order" alarm
 *    and one "cancelled order" alarm, no matter how many times the listener restarts.
 *
 * 2) THE "CANCELLED ORDERS DISAPPEAR" REQUIREMENT
 *    The `orders` collection has no `cancelledAt` field we can query on, so we can't ask
 *    Firestore "show me orders cancelled in the last 10 minutes". Instead, the moment we
 *    *observe* an order flip to "Cancelled" we stamp it locally with the current time and
 *    whether the partner had already progressed it (Accepted/Ready). The UI then uses that
 *    stamp to keep it visible for a 10 minute grace period.
 */
object OrderAlertTracker {
    private const val PREFS_NAME = "YumzyPartnerPrefs"
    private const val KEY_ALERTED_NEW = "alerted_new_order_ids"
    private const val KEY_ALERTED_CANCEL = "alerted_cancel_order_ids"
    private const val KEY_CANCEL_META = "cancel_meta_entries" // encoded "orderId|timestamp|hadProgress"

    const val CANCEL_GRACE_PERIOD_MS = 10 * 60 * 1000L // 10 minutes

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------- New-order alarm dedup ----------------

    fun hasAlertedNewOrder(context: Context, orderId: String): Boolean =
        prefs(context).getStringSet(KEY_ALERTED_NEW, emptySet())?.contains(orderId) == true

    fun markNewOrderAlerted(context: Context, orderId: String) {
        val p = prefs(context)
        val current = (p.getStringSet(KEY_ALERTED_NEW, emptySet()) ?: emptySet()).toMutableSet()
        current.add(orderId)
        p.edit().putStringSet(KEY_ALERTED_NEW, current).apply()
    }

    // ---------------- Cancelled-order alarm dedup ----------------

    fun hasAlertedCancelledOrder(context: Context, orderId: String): Boolean =
        prefs(context).getStringSet(KEY_ALERTED_CANCEL, emptySet())?.contains(orderId) == true

    fun markCancelledOrderAlerted(context: Context, orderId: String) {
        val p = prefs(context)
        val current = (p.getStringSet(KEY_ALERTED_CANCEL, emptySet()) ?: emptySet()).toMutableSet()
        current.add(orderId)
        p.edit().putStringSet(KEY_ALERTED_CANCEL, current).apply()
    }

    // ---------------- Cancellation metadata (first-seen time + progress flag) ----------------

    data class CancelMeta(val orderId: String, val firstSeenAt: Long, val hadProgress: Boolean)

    private fun readAllMeta(context: Context): MutableList<CancelMeta> {
        val raw = prefs(context).getStringSet(KEY_CANCEL_META, emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size != 3) return@mapNotNull null
            val ts = parts[1].toLongOrNull() ?: return@mapNotNull null
            CancelMeta(orderId = parts[0], firstSeenAt = ts, hadProgress = parts[2] == "1")
        }.toMutableList()
    }

    private fun writeAllMeta(context: Context, list: List<CancelMeta>) {
        val encoded = list.map { "${it.orderId}|${it.firstSeenAt}|${if (it.hadProgress) "1" else "0"}" }.toSet()
        prefs(context).edit().putStringSet(KEY_CANCEL_META, encoded).apply()
    }

    fun getCancelMeta(context: Context, orderId: String): CancelMeta? =
        readAllMeta(context).firstOrNull { it.orderId == orderId }

    /** Records (once) the moment this order was first observed as Cancelled. Idempotent. */
    fun recordCancelMeta(context: Context, orderId: String, hadProgress: Boolean): CancelMeta {
        getCancelMeta(context, orderId)?.let { return it }
        val meta = CancelMeta(orderId, System.currentTimeMillis(), hadProgress)
        val list = readAllMeta(context)
        list.add(meta)
        writeAllMeta(context, list)
        return meta
    }

    fun clearCancelMeta(context: Context, orderId: String) {
        val list = readAllMeta(context).filterNot { it.orderId == orderId }
        writeAllMeta(context, list)
    }

    /** Call occasionally (e.g. app start) to stop the pref set from growing forever. */
    fun purgeExpiredCancelMeta(context: Context) {
        val now = System.currentTimeMillis()
        val kept = readAllMeta(context).filter { now - it.firstSeenAt < CANCEL_GRACE_PERIOD_MS }
        writeAllMeta(context, kept)
    }
}