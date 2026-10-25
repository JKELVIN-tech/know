package com.knowapp.android.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.knowapp.android.data.model.StockItemOut
import java.math.BigDecimal

// Tells the owner or staff when stock runs low, once per change, using the item's own alert level or 5
object LowStockNotifier {
    private const val CHANNEL = "low_stock"
    private const val PREFS = "know_low_stock"

    fun check(context: Context, items: List<StockItemOut>, askPermission: () -> Unit) {
        val low = items.filter {
            val qty = it.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
            qty <= (it.lowStockThreshold?.toBigDecimalOrNull() ?: BigDecimal("5"))
        }.sortedBy { it.name }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val signature = low.joinToString("|") { "${it.id}:${if ((it.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO) <= BigDecimal.ZERO) "out" else "low"}" }
        if (low.isEmpty()) {
            prefs.edit().putString("last", "").apply()
            return
        }
        if (signature == prefs.getString("last", "")) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                askPermission()
            }
            return
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Low stock", NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val names = low.take(3).joinToString(", ") { it.name } + if (low.size > 3) " and ${low.size - 3} more" else ""
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(if (low.size == 1) "1 item is running low" else "${low.size} items are running low")
            .setContentText(names)
            .setStyle(NotificationCompat.BigTextStyle().bigText(names))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(1001, notification)
            prefs.edit().putString("last", signature).apply()
        } catch (_: SecurityException) { }
    }
}
