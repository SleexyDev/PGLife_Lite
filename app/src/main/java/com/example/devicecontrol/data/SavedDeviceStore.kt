package com.example.devicecontrol.data

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

data class SavedDevice(
    val goodsId: String,
    val id: String? = null,
    val goodsName: String = "",
    val rawUrl: String? = null,
    val sn: String? = null,
    val imei: String? = null,
    val skuIds: List<String> = emptyList(),
    val deviceType: String? = null,
    val categoryName: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
) {
    fun toDeviceItem(): DeviceItem = DeviceItem(
        goodsId = goodsId,
        goodsName = goodsName,
        id = id ?: goodsId,
    )
}

class SavedDeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved_devices", Context.MODE_PRIVATE)
    private val adapter = Moshi.Builder()
        .add(LenientStringJsonAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<SavedDevice>>(
            Types.newParameterizedType(List::class.java, SavedDevice::class.java),
        )

    fun list(): List<SavedDevice> {
        val raw = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return runCatching { adapter.fromJson(raw).orEmpty() }.getOrDefault(emptyList())
    }

    fun upsert(device: SavedDevice) {
        val next = listOf(device) + list().filterNot { it.goodsId == device.goodsId }
        prefs.edit().putString(KEY_DEVICES, adapter.toJson(next)).apply()
    }

    fun remove(goodsId: String) {
        val next = list().filterNot { it.goodsId == goodsId }
        prefs.edit().putString(KEY_DEVICES, adapter.toJson(next)).apply()
    }

    private companion object {
        const val KEY_DEVICES = "devices"
    }
}
