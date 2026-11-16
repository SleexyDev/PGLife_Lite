package com.example.devicecontrol.data

import android.net.Uri
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit

class AppRepository(
    private val tokenStore: TokenStore,
    private val orderHistoryStore: OrderHistoryStore,
    private val savedDeviceStore: SavedDeviceStore,
) {
    private val api: DeviceApi

    init {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor { tokenStore.readToken() })
            .build()
        val moshi = Moshi.Builder()
            .add(EmptyDataJsonAdapter())
            .add(LenientStringJsonAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()

        api = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DeviceApi::class.java)
    }

    fun localToken(): String? = tokenStore.readToken()

    fun localPhone(): String? = tokenStore.readPhone()

    fun orderHistory(): List<OrderHistoryItem> = orderHistoryStore.list()

    fun savedDevices(): List<SavedDevice> = savedDeviceStore.list()

    fun removeSavedDevice(goodsId: String) {
        savedDeviceStore.remove(goodsId)
    }

    suspend fun sendCode(phone: String) {
        api.sendCode(phone = phone).throwIfFailed()
    }

    suspend fun login(phone: String, code: String): String {
        val token = api.login(phone = phone, verify = code).requireData().token
            ?: error("登录成功但未返回 token")
        tokenStore.saveToken(token)
        tokenStore.savePhone(phone)
        return token
    }

    suspend fun queryBalance(): BalanceData {
        val token = requireToken()
        return api.queryBalance(token).requireData()
    }

    suspend fun latestDevices(): List<DeviceItem> {
        val token = requireToken()
        return api.getLatestUsed(token = token).requireData()
    }

    suspend fun unlockDevice(
        device: DeviceItem,
        onStep: suspend (String) -> Unit,
    ): UnlockResult {
        val token = requireToken()
        val goodsId = device.goodsId ?: device.id ?: error("设备缺少 goodsId")

        onStep("正在获取 SKU")
        val skuId = api.goodsid2sku(goodsId = goodsId, token = token).requireData().firstOrNull()?.skuId
            ?: error("未获取到 skuId")

        onStep("正在获取 IMEI")
        val imei = api.getImei(goodsId = goodsId, token = token).requireData().imei
            ?: error("未获取到 imei")

        onStep("正在检查积分")
        api.useIntergral(token).throwIfFailed()

        onStep("正在开通后付")
        api.addUserAfterPayChannel(token = token).throwIfFailed()

        onStep("正在检查位置风控")
        api.isCheckLocation(imei = imei, token = token).throwIfFailed()

        onStep("正在启动解锁")
        val unlock = api.unlockWater(
            skuId = skuId,
            promotions = PROMOTIONS,
            token = token,
        ).requireData()

        val orderNo = unlock.orderNo ?: error("未获取到订单号")
        onStep("设备已启动，等待使用结束")

        var lastStatus: SyncData
        do {
            delay(1000)
            lastStatus = api.syncWater(skuId = skuId, token = token).requireData()
            onStep("设备工作中，正在等待完成")
        } while (lastStatus.workStatus == 2)

        val finalOrderNo = lastStatus.identify ?: orderNo
        onStep("正在创建后付订单")
        val orderId = api.createAfterPay(orderNo = finalOrderNo, token = token).requireData().orderId
            ?: error("未获取到 orderId")

        onStep("正在查询订单详情")
        val detail = api.orderDetail(orderId = orderId, token = token).requireData()
        val ticketCost = detail.promotionList.firstOrNull { it.promotionType == 4 }?.discountAmount ?: "-"
        val integralCost = detail.promotionList.firstOrNull { it.promotionType == 8 }?.discountAmount ?: "-"
        val otherPromotions = detail.promotionList
            .filter { it.promotionType != 4 && it.promotionType != 8 }
            .map {
                PromotionSummary(
                    promotionType = it.promotionType,
                    discountAmount = it.discountAmount,
                )
            }

        val result = UnlockResult(
            orderNo = finalOrderNo,
            orderId = orderId,
            originPrice = detail.tradeOrderItem.firstOrNull()?.originPrice ?: "-",
            ticketCost = ticketCost,
            integralCost = integralCost,
            otherPromotions = otherPromotions,
            completedAt = System.currentTimeMillis(),
        )
        orderHistoryStore.add(
            OrderHistoryItem(
                orderNo = result.orderNo,
                orderId = result.orderId,
                goodsName = device.goodsName.ifBlank { "未命名设备" },
                originPrice = result.originPrice,
                ticketCost = result.ticketCost,
                integralCost = result.integralCost,
                otherPromotions = result.otherPromotions,
                completedAt = result.completedAt,
            ),
        )
        return result
    }

    /**
     * Resolves a scanned QR code or manual link to a device and saves it locally.
     */
    suspend fun addDeviceFromScan(
        rawText: String,
        knownDevices: List<DeviceItem>,
    ): SavedDevice {
        val token = requireToken()
        val parsed = parseQrParameters(rawText)

        var resolvedId: String? = null
        var resolvedGoodsId: String? = null
        var resolvedName = ""
        var deviceType: String? = null
        var categoryName: String? = null
        var resolvedImei: String? = null
        var scanError: String? = null

        val scanFields = buildMap {
            parsed.nqtParam?.let { put("NQT", it) }
            parsed.sn?.let { put("SN", it) }
            parsed.imeiParam?.let { put("IMEI", it) }
            if (isEmpty() && parsed.trimmedRaw.isNotBlank()) {
                put("NQT", parsed.trimmedRaw)
            }
        }
        if (scanFields.isNotEmpty()) {
            val scanResult = readRawJson { api.scanDeviceV2(scanFields + ("token" to token)) }
            val data = scanResult.data
            if (data != null) {
                resolvedGoodsId = data.optString("goodsId").ifBlank { data.optString("id") }.takeIf { it.isNotBlank() }
                resolvedId = data.optString("id").takeIf { it.isNotBlank() } ?: resolvedGoodsId
                categoryName = data.optString("categoryName").takeIf { it.isNotBlank() }
                deviceType = data.optString("type").takeIf { it.isNotBlank() }
                resolvedName = listOf("goodsName", "name", "deviceName", "categoryName")
                    .firstNotNullOfOrNull { key -> data.optString(key).takeIf { it.isNotBlank() } }
                    .orEmpty()
            } else {
                scanError = scanResult.errorMessage
            }
        }

        if (resolvedGoodsId == null && !parsed.queryGoodsId.isNullOrBlank()) {
            resolvedGoodsId = parsed.queryGoodsId
            resolvedId = parsed.queryGoodsId
        }

        val hardwareId = parsed.sn ?: parsed.imeiParam
        if (resolvedGoodsId == null && !hardwareId.isNullOrBlank()) {
            val candidates = knownDevices.ifEmpty { runCatching { latestDevices() }.getOrDefault(emptyList()) }
            for (candidate in candidates) {
                val candidateGoodsId = candidate.goodsId ?: candidate.id ?: continue
                val imei = runCatching { api.getImei(goodsId = candidateGoodsId, token = token).data?.imei }.getOrNull()
                if (imei == hardwareId) {
                    resolvedGoodsId = candidateGoodsId
                    resolvedId = candidate.id ?: candidateGoodsId
                    resolvedName = candidate.goodsName
                    resolvedImei = imei
                    break
                }
            }
        }

        val finalGoodsId = resolvedGoodsId ?: error(scanError ?: "未能识别该设备二维码")
        val enriched = fetchDetailsInternal(
            goodsId = finalGoodsId,
            token = token,
            fallbackName = resolvedName,
            fallbackImei = resolvedImei,
        )

        val saved = SavedDevice(
            goodsId = finalGoodsId,
            id = resolvedId ?: finalGoodsId,
            goodsName = enriched.name.ifBlank { "未命名设备" },
            rawUrl = parsed.trimmedRaw.takeIf { it.isNotBlank() },
            sn = parsed.sn ?: parsed.imeiParam,
            imei = enriched.imei,
            skuIds = enriched.skuIds,
            deviceType = deviceType,
            categoryName = categoryName,
            savedAt = System.currentTimeMillis(),
        )
        savedDeviceStore.upsert(saved)
        return saved
    }

    /**
     * Loads up-to-date details (IMEI, SKUs, name) for a device shown in the control list.
     */
    suspend fun fetchDeviceDetails(
        device: DeviceItem,
        existing: SavedDevice?,
    ): SavedDevice {
        val goodsId = device.goodsId ?: device.id ?: error("设备缺少 goodsId")
        val token = tokenStore.readToken()?.takeIf { it.isNotBlank() }
        if (token == null) {
            return existing ?: SavedDevice(
                goodsId = goodsId,
                id = device.id ?: goodsId,
                goodsName = device.goodsName,
            )
        }

        val enriched = fetchDetailsInternal(
            goodsId = goodsId,
            token = token,
            fallbackName = existing?.goodsName?.ifBlank { device.goodsName } ?: device.goodsName,
            fallbackImei = existing?.imei,
        )
        val merged = SavedDevice(
            goodsId = goodsId,
            id = existing?.id ?: device.id ?: goodsId,
            goodsName = enriched.name.ifBlank { device.goodsName.ifBlank { "未命名设备" } },
            rawUrl = existing?.rawUrl,
            sn = existing?.sn ?: enriched.imei,
            imei = enriched.imei,
            skuIds = enriched.skuIds.ifEmpty { existing?.skuIds.orEmpty() },
            deviceType = existing?.deviceType,
            categoryName = existing?.categoryName,
            savedAt = existing?.savedAt ?: System.currentTimeMillis(),
        )
        if (existing != null) {
            savedDeviceStore.upsert(merged)
        }
        return merged
    }

    private data class EnrichedDetails(
        val name: String,
        val imei: String?,
        val skuIds: List<String>,
    )

    private suspend fun fetchDetailsInternal(
        goodsId: String,
        token: String,
        fallbackName: String,
        fallbackImei: String?,
    ): EnrichedDetails {
        val detailObj = readRawJson { api.getNormalDetailsRaw(goodsId = goodsId, token = token) }.data
        val imei = detailObj?.optString("imei")?.takeIf { it.isNotBlank() } ?: fallbackImei
        val detailName = detailObj?.let { obj ->
            listOf("goodsName", "name", "deviceName")
                .firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
        }
        val skuIds = runCatching {
            api.goodsid2sku(goodsId = goodsId, token = token).requireData()
                .mapNotNull { it.skuId?.takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())

        return EnrichedDetails(
            name = detailName ?: fallbackName,
            imei = imei,
            skuIds = skuIds,
        )
    }

    private data class RawJsonResult(
        val data: JSONObject?,
        val errorMessage: String?,
    )

    private suspend fun readRawJson(
        call: suspend () -> Response<ResponseBody>,
    ): RawJsonResult {
        return runCatching {
            val response = call()
            val raw = (if (response.isSuccessful) response.body()?.string() else response.errorBody()?.string()).orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val dataObj = json?.optJSONObject("data")
            val msg = json?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json?.optString("msg")?.takeIf { it.isNotBlank() }
                ?: if (!response.isSuccessful) "请求失败 (HTTP ${response.code()})" else null
            RawJsonResult(data = dataObj, errorMessage = msg)
        }.getOrElse { error ->
            RawJsonResult(data = null, errorMessage = error.message ?: "网络请求失败")
        }
    }

    private fun parseQrParameters(rawText: String): ParsedQrParams {
        val trimmed = rawText.trim()
        val firstQuestion = trimmed.indexOf('?')
        val normalized = if (firstQuestion >= 0) {
            trimmed.substring(0, firstQuestion + 1) +
                trimmed.substring(firstQuestion + 1).replace('?', '&')
        } else {
            trimmed
        }
        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        val queryParams = buildMap {
            if (uri != null && uri.isHierarchical) {
                uri.queryParameterNames.forEach { name ->
                    val value = uri.getQueryParameter(name)?.trim()
                    if (!value.isNullOrEmpty()) {
                        put(name.lowercase(), value)
                    }
                }
            }
        }
        return ParsedQrParams(
            trimmedRaw = trimmed,
            sn = queryParams["sn"],
            nqtParam = queryParams["nqt"],
            imeiParam = queryParams["imei"],
            queryGoodsId = queryParams["goodsid"],
        )
    }

    private data class ParsedQrParams(
        val trimmedRaw: String,
        val sn: String?,
        val nqtParam: String?,
        val imeiParam: String?,
        val queryGoodsId: String?,
    )

    private fun requireToken(): String = tokenStore.readToken()?.takeIf { it.isNotBlank() }
        ?: error("请先登录")

    private fun ApiEnvelope<*>.throwIfFailed() {
        if (code != null && code != 0 && code != 200) {
            error(message ?: msg ?: "请求失败")
        }
    }

    private companion object {
        const val PROMOTIONS =
            """[{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-6"},{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-7"},{"assetId":"0","oldPromotionId":"0","orgId":"0","promotionId":"0","promotionType":"8"}]"""
    }
}
