package com.knowapp.android.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// A message detected on this phone that is waiting for the user to review it; only parsed fields are kept, never the message text
data class DetectedMessage(
    val id: Int,
    val code: String,
    val kind: String,
    val direction: String?,
    val amount: String?,
    val counterparty: String?,
    val status: String,
    val reason: String?,
)

// Keeps detected messages on the phone per signed-in user, so they survive restarts and need no connection
class DetectedStore(context: Context, private val userId: () -> Int?) {
    private val prefs = context.applicationContext.getSharedPreferences("know_detected", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<DetectedMessage>>() {}.type

    fun load(): List<DetectedMessage> {
        val uid = userId() ?: return emptyList()
        return try { gson.fromJson<List<DetectedMessage>>(prefs.getString("items_$uid", null), type) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun save(items: List<DetectedMessage>) {
        val uid = userId() ?: return
        prefs.edit().putString("items_$uid", gson.toJson(items.takeLast(300))).apply()
    }
}
