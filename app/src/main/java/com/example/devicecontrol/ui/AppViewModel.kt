package com.example.devicecontrol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.devicecontrol.data.AppRepository
import com.example.devicecontrol.data.BalanceData
import com.example.devicecontrol.data.DeviceItem
import com.example.devicecontrol.data.SavedDevice
import com.example.devicecontrol.data.OrderHistoryItem
import com.example.devicecontrol.data.UnlockResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DeviceTab { Control, Me }

data class AppUiState(
    val currentTab: DeviceTab = DeviceTab.Control,
    val hasToken: Boolean = false,
    val phone: String = "",
    val code: String = "",
    val sendingCode: Boolean = false,
    val sendCodeCooldownSeconds: Int = 0,
    val loggingIn: Boolean = false,
    val loadingDevices: Boolean = false,
    val loadingBalance: Boolean = false,
    val unlocking: Boolean = false,
    val devices: List<DeviceItem> = emptyList(),
    val balance: BalanceData? = null,
    val orderHistory: List<OrderHistoryItem> = emptyList(),
    val pendingUnlockDevice: DeviceItem? = null,
    val unlockStatus: String? = null,
    val orderDetail: UnlockResult? = null,
    val showOrderHistory: Boolean = false,
    val tokenDialogText: String? = null,
    val showScanner: Boolean = false,
    val scanningLookup: Boolean = false,
    val savedDeviceIds: Set<String> = emptySet(),
    val deviceDetailsMap: Map<String, SavedDevice> = emptyMap(),
    val deviceDetailDialog: SavedDevice? = null,
    val loadingDeviceDetail: Boolean = false,
    val pendingRemoveDevice: DeviceItem? = null,
    val toastMessage: String? = null,
    val errorMessage: String? = null,
)

class AppViewModel(
    private val repository: AppRepository,
) : ViewModel() {
    private var sendCodeCooldownJob: Job? = null
    private var serverDevices: List<DeviceItem> = emptyList()
    private val initialSaved = repository.savedDevices()
    private val _state = MutableStateFlow(
        AppUiState(
            hasToken = repository.localToken() != null,
            phone = repository.localPhone().orEmpty(),
            devices = buildMergedDevices(initialSaved, emptyList()),
            savedDeviceIds = initialSaved.map { it.goodsId }.toSet(),
            deviceDetailsMap = initialSaved.associateBy { it.goodsId },
            orderHistory = repository.orderHistory(),
        ),
    )
    val state: StateFlow<AppUiState> = _state

    init {
        if (repository.localToken() != null) {
            refreshDevices()
            refreshBalance()
        }
    }

    fun selectTab(tab: DeviceTab) {
        _state.update { it.copy(currentTab = tab) }
        if (tab == DeviceTab.Control && state.value.hasToken && state.value.devices.isEmpty()) {
            refreshDevices()
        }
    }

    fun updatePhone(value: String) {
        _state.update { it.copy(phone = value) }
    }

    fun updateCode(value: String) {
        _state.update { it.copy(code = value) }
    }

    fun sendCode() {
        if (state.value.sendingCode || state.value.sendCodeCooldownSeconds > 0) return
        val phone = state.value.phone.trim()
        if (phone.isBlank()) {
            showError("请输入手机号")
            return
        }
        _state.update { it.copy(sendingCode = true) }
        viewModelScope.launch {
            runCatching {
                repository.sendCode(phone)
            }.onSuccess {
                showToast("验证码已发送")
                startSendCodeCooldown()
            }.onFailure {
                showError(it.message ?: "验证码发送失败")
            }
            _state.update { it.copy(sendingCode = false) }
        }
    }

    private fun startSendCodeCooldown() {
        sendCodeCooldownJob?.cancel()
        sendCodeCooldownJob = viewModelScope.launch {
            for (remaining in SEND_CODE_COOLDOWN_SECONDS downTo 1) {
                _state.update { it.copy(sendCodeCooldownSeconds = remaining) }
                delay(1000)
            }
            _state.update { it.copy(sendCodeCooldownSeconds = 0) }
        }
    }

    fun login() = viewModelScope.launch {
        val phone = state.value.phone.trim()
        val code = state.value.code.trim()
        if (phone.isBlank() || code.isBlank()) {
            showError("请输入手机号和验证码")
            return@launch
        }
        runCatching {
            _state.update { it.copy(loggingIn = true) }
            repository.login(phone, code)
        }.onSuccess {
            _state.update { it.copy(hasToken = true, phone = phone, loggingIn = false) }
            showToast("登录成功")
            refreshBalance()
            refreshDevices()
        }.onFailure {
            _state.update { it.copy(loggingIn = false) }
            showError(it.message ?: "登录失败")
        }
    }

    fun refreshDevices() = viewModelScope.launch {
        if (!state.value.hasToken) return@launch
        runCatching {
            _state.update { it.copy(loadingDevices = true) }
            repository.latestDevices()
        }.onSuccess { devices ->
            serverDevices = devices
            val saved = repository.savedDevices()
            val merged = buildMergedDevices(saved, devices)
            _state.update {
                it.copy(
                    devices = merged,
                    savedDeviceIds = saved.map { d -> d.goodsId }.toSet(),
                    deviceDetailsMap = it.deviceDetailsMap + saved.associateBy { d -> d.goodsId },
                    loadingDevices = false,
                )
            }
        }.onFailure {
            _state.update { it.copy(loadingDevices = false) }
            showError(it.message ?: "查询历史设备失败")
        }
    }

    fun refreshBalance() = viewModelScope.launch {
        if (!state.value.hasToken) return@launch
        runCatching {
            _state.update { it.copy(loadingBalance = true) }
            repository.queryBalance()
        }.onSuccess { balance ->
            _state.update { it.copy(balance = balance, loadingBalance = false) }
        }.onFailure {
            _state.update { it.copy(loadingBalance = false) }
            showError(it.message ?: "查询资产失败")
        }
    }

    fun requestUnlock(device: DeviceItem) {
        if (state.value.unlocking) return
        _state.update { it.copy(pendingUnlockDevice = device) }
    }

    fun dismissUnlockConfirm() {
        _state.update { it.copy(pendingUnlockDevice = null) }
    }

    fun confirmUnlock() {
        val device = state.value.pendingUnlockDevice ?: return
        _state.update { it.copy(pendingUnlockDevice = null) }
        unlock(device)
    }

    fun openScanner() {
        _state.update { it.copy(showScanner = true) }
    }

    fun closeScanner() {
        _state.update { it.copy(showScanner = false) }
    }

    fun onQrScanned(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isBlank() || state.value.scanningLookup) return
        _state.update { it.copy(showScanner = false, scanningLookup = true) }
        viewModelScope.launch {
            runCatching {
                repository.addDeviceFromScan(trimmed, state.value.devices)
            }.onSuccess { saved ->
                val allSaved = repository.savedDevices()
                val merged = buildMergedDevices(allSaved, serverDevices)
                _state.update {
                    it.copy(
                        scanningLookup = false,
                        devices = merged,
                        savedDeviceIds = allSaved.map { d -> d.goodsId }.toSet(),
                        deviceDetailsMap = it.deviceDetailsMap + allSaved.associateBy { d -> d.goodsId },
                    )
                }
                showToast("已添加：${saved.goodsName.ifBlank { saved.goodsId }}")
            }.onFailure { err ->
                _state.update { it.copy(scanningLookup = false) }
                showError(err.message ?: "扫码添加设备失败")
            }
        }
    }

    fun openDeviceDetails(device: DeviceItem) {
        val key = device.goodsId ?: device.id
        val existing = key?.let { state.value.deviceDetailsMap[it] }
        val initial = existing ?: SavedDevice(
            goodsId = key.orEmpty(),
            id = device.id,
            goodsName = device.goodsName.ifBlank { "未命名设备" },
        )
        _state.update {
            it.copy(
                deviceDetailDialog = initial,
                loadingDeviceDetail = state.value.hasToken && !key.isNullOrBlank(),
            )
        }
        if (!state.value.hasToken || key.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.fetchDeviceDetails(device, existing)
            }.onSuccess { enriched ->
                _state.update { current ->
                    current.copy(
                        loadingDeviceDetail = false,
                        deviceDetailsMap = current.deviceDetailsMap + (enriched.goodsId to enriched),
                        deviceDetailDialog = if (current.deviceDetailDialog?.goodsId == enriched.goodsId) {
                            enriched
                        } else {
                            current.deviceDetailDialog
                        },
                    )
                }
            }.onFailure {
                _state.update { it.copy(loadingDeviceDetail = false) }
            }
        }
    }

    fun dismissDeviceDetail() {
        _state.update { it.copy(deviceDetailDialog = null, loadingDeviceDetail = false) }
    }

    fun requestRemoveDevice(device: DeviceItem) {
        _state.update { it.copy(pendingRemoveDevice = device) }
    }

    fun dismissRemoveConfirm() {
        _state.update { it.copy(pendingRemoveDevice = null) }
    }

    fun confirmRemoveDevice() {
        val device = state.value.pendingRemoveDevice ?: return
        _state.update { it.copy(pendingRemoveDevice = null) }
        val key = device.goodsId ?: device.id ?: return
        removeSavedDevice(key)
    }

    private fun removeSavedDevice(goodsId: String) {
        repository.removeSavedDevice(goodsId)
        val allSaved = repository.savedDevices()
        val merged = buildMergedDevices(allSaved, serverDevices)
        _state.update { current ->
            current.copy(
                devices = merged,
                savedDeviceIds = allSaved.map { d -> d.goodsId }.toSet(),
                deviceDetailsMap = current.deviceDetailsMap - goodsId,
                deviceDetailDialog = if (current.deviceDetailDialog?.goodsId == goodsId) null else current.deviceDetailDialog,
            )
        }
        showToast("已移除设备")
    }

    private fun unlock(device: DeviceItem) = viewModelScope.launch {
        if (state.value.unlocking) return@launch
        runCatching {
            _state.update { it.copy(unlocking = true, unlockStatus = "准备解锁") }
            repository.unlockDevice(device) { step ->
                _state.update { it.copy(unlockStatus = step) }
            }
        }.onSuccess { result ->
            _state.update {
                it.copy(
                    unlocking = false,
                    unlockStatus = null,
                    orderDetail = result,
                    orderHistory = repository.orderHistory(),
                )
            }
            showToast("解锁成功！订单原价：${result.originPrice}，花费小票：${result.ticketCost}")
            refreshBalance()
        }.onFailure {
            _state.update { it.copy(unlocking = false, unlockStatus = null) }
            showError(it.message ?: "解锁失败")
        }
    }


    fun showCurrentToken() {
        val token = repository.localToken()?.takeIf { it.isNotBlank() }
        _state.update { it.copy(tokenDialogText = token ?: "当前未登录，暂无 Token") }
    }

    fun dismissCurrentToken() {
        _state.update { it.copy(tokenDialogText = null) }
    }
    fun consumeToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun dismissOrderDetail() {
        _state.update { it.copy(orderDetail = null) }
    }

    fun showOrderHistory() {
        _state.update { it.copy(showOrderHistory = true, orderHistory = repository.orderHistory()) }
    }

    fun dismissOrderHistory() {
        _state.update { it.copy(showOrderHistory = false) }
    }

    fun showHistoricalOrder(item: OrderHistoryItem) {
        _state.update { it.copy(orderDetail = item.toUnlockResult(), showOrderHistory = false) }
    }

    private fun showToast(message: String) {
        _state.update { it.copy(toastMessage = message) }
    }

    private fun showError(message: String) {
        _state.update { it.copy(errorMessage = message) }
    }

    private companion object {
        const val SEND_CODE_COOLDOWN_SECONDS = 60

        fun buildMergedDevices(
            saved: List<SavedDevice>,
            remote: List<DeviceItem>,
        ): List<DeviceItem> {
            val remoteByKey = remote.mapNotNull { item ->
                val key = item.goodsId ?: item.id
                if (key.isNullOrBlank()) null else key to item
            }.toMap()
            val savedKeys = saved.map { it.goodsId }.toSet()
            val savedItems = saved.map { entry ->
                val fallbackName = remoteByKey[entry.goodsId]?.goodsName.orEmpty()
                entry.toDeviceItem().let { item ->
                    if (item.goodsName.isBlank() && fallbackName.isNotBlank()) {
                        item.copy(goodsName = fallbackName)
                    } else {
                        item
                    }
                }
            }
            val remainingRemote = remote.filter { item ->
                val key = item.goodsId ?: item.id
                key == null || key !in savedKeys
            }
            return savedItems + remainingRemote
        }
    }
}

class AppViewModelFactory(
    private val repository: AppRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppViewModel(repository) as T
    }
}
