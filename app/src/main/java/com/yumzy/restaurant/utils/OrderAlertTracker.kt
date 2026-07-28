package com.yumzy.restaurant.utils

import android.content.Context

/**
 * Lightweight, persistent tracker used by OrderMonitorService so a given order never triggers
 * more than one "new order" alarm and more than one "cancelled order" alarm, no matter how many
 * times the Firestore listener restarts (service recreation, phone reboot, app relaunch, etc.).
 *
 * NOTE: This tracker is ONLY used for alarm de-duplication. It intentionally does NOT store any
 * "first seen" timestamps or "had progress" flags anymore - the 10 minute "Recently Cancelled"
 * grace period shown in the UI is computed directly from the order's own `createdAt` time, so
 * every device (and every reinstall) sees exactly the same countdown with no local state to
 * fall out of sync.
 */
object OrderAlertTracker {
    private const val PREFS_NAME = "YumzyPartnerPrefs"
    private const val KEY_ALERTED_NEW = "alerted_new_order_ids"
    private const val KEY_ALERTED_CANCEL = "alerted_cancel_order_ids"

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
}