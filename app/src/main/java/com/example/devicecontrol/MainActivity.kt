package com.example.devicecontrol

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.devicecontrol.data.AppRepository
import com.example.devicecontrol.data.DeviceItem
import com.example.devicecontrol.data.OrderHistoryItem
import com.example.devicecontrol.data.OrderHistoryStore
import com.example.devicecontrol.data.SavedDevice
import com.example.devicecontrol.data.SavedDeviceStore
import com.example.devicecontrol.data.TokenStore
import com.example.devicecontrol.data.UnlockResult
import com.example.devicecontrol.ui.AppViewModel
import com.example.devicecontrol.ui.AppViewModelFactory
import com.example.devicecontrol.ui.DeviceTab
import com.example.devicecontrol.ui.QrScannerDialog
import com.example.devicecontrol.ui.theme.DeviceControlTheme

private const val PROJECT_URL = "https://github.com/SleexyDev/PGLife_Lite"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeviceControlTheme {
                val repository = remember {
                    AppRepository(
                        tokenStore = TokenStore(applicationContext),
                        orderHistoryStore = OrderHistoryStore(applicationContext),
                        savedDeviceStore = SavedDeviceStore(applicationContext),
                    )
                }
                val vm: AppViewModel = viewModel(
                    factory = AppViewModelFactory(repository),
                )
                DeviceControlApp(vm)
            }
        }
    }
}

private fun openProjectHome(context: Context) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
    }.onFailure {
        Toast.makeText(context, "未找到可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun DeviceControlApp(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.toastMessage) {
        val message = state.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        vm.consumeToast()
    }

    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeError()
    }

    state.tokenDialogText?.let { token ->
        TokenDialog(token = token, onDismiss = vm::dismissCurrentToken)
    }

    if (state.showOrderHistory) {
        OrderHistoryDialog(
            orders = state.orderHistory,
            onDismiss = vm::dismissOrderHistory,
            onOpenOrder = vm::showHistoricalOrder,
        )
    }

    state.orderDetail?.let { detail ->
        OrderDetailDialog(
            detail = detail,
            onDismiss = vm::dismissOrderDetail,
        )
    }

    state.pendingUnlockDevice?.let { device ->
        UnlockConfirmDialog(
            device = device,
            onDismiss = vm::dismissUnlockConfirm,
            onConfirm = vm::confirmUnlock,
        )
    }

    if (state.showScanner) {
        QrScannerDialog(
            onDismiss = vm::closeScanner,
            onCodeDetected = vm::onQrScanned,
        )
    }

    if (state.scanningLookup) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在添加设备") },
            text = { LoadingPanel("正在识别并添加到设备列表…") },
            confirmButton = {},
            shape = RoundedCornerShape(24.dp),
        )
    }

    state.deviceDetailDialog?.let { detail ->
        DeviceDetailDialog(
            detail = detail,
            loading = state.loadingDeviceDetail,
            onCopyLink = { url -> copyToClipboard(context, url, "已复制设备链接") },
            onDismiss = vm::dismissDeviceDetail,
        )
    }

    state.pendingRemoveDevice?.let { device ->
        RemoveDeviceConfirmDialog(
            device = device,
            onDismiss = vm::dismissRemoveConfirm,
            onConfirm = vm::confirmRemoveDevice,
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = state.currentTab == DeviceTab.Control,
                    onClick = { vm.selectTab(DeviceTab.Control) },
                    icon = { androidx.compose.material3.Icon(Icons.Outlined.Home, contentDescription = null) },
                    label = { Text("设备控制") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
                NavigationBarItem(
                    selected = state.currentTab == DeviceTab.Me,
                    onClick = { vm.selectTab(DeviceTab.Me) },
                    icon = { androidx.compose.material3.Icon(Icons.Outlined.Person, contentDescription = null) },
                    label = { Text("我的") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (state.currentTab) {
                DeviceTab.Control -> ControlScreen(state, vm)
                DeviceTab.Me -> MeScreen(state, vm)
            }
        }
    }
}

@Composable
private fun UnlockConfirmDialog(
    device: DeviceItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val deviceName = device.goodsName.ifBlank { "未命名设备" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认开启设备") },
        text = { Text("即将开启「$deviceName」，确认开始使用吗？") },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("确认开启")
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
private fun RemoveDeviceConfirmDialog(
    device: DeviceItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val deviceName = device.goodsName.ifBlank { "未命名设备" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移除设备") },
        text = { Text("确定要移除「$deviceName」吗？") },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("确认移除")
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
private fun DeviceDetailDialog(
    detail: SavedDevice,
    loading: Boolean,
    onCopyLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.goodsName.ifBlank { "设备详情" }) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailLine("设备名称", detail.goodsName.ifBlank { "未命名设备" })
                DetailLine("goodsId", detail.goodsId.ifBlank { "-" })
                DetailLine("SN", detail.sn ?: "-")
                DetailLine("IMEI", detail.imei ?: "-")
                DetailLine("SKU", if (detail.skuIds.isEmpty()) "-" else detail.skuIds.joinToString())
                if (!detail.categoryName.isNullOrBlank()) {
                    DetailLine("品类", detail.categoryName)
                }
                if (!detail.deviceType.isNullOrBlank()) {
                    DetailLine("类型", detail.deviceType)
                }
                if (loading) {
                    Text(
                        text = "正在同步最新参数…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!detail.rawUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("设备链接（点击复制）", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Surface(
                        onClick = { onCopyLink(detail.rawUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = detail.rawUrl,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                } else {
                    DetailLine("设备链接", "-")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

private fun copyToClipboard(context: Context, text: String, toast: String) {
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager?.setPrimaryClip(ClipData.newPlainText("device_link", text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}


@Composable
private fun TokenDialog(token: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前 Token") },
        text = {
            Text(
                text = token,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}
@Composable
private fun OrderDetailDialog(
    detail: UnlockResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("订单详情") },
        text = {
            Column {
                DetailLine("订单号", detail.orderNo)
                DetailLine("订单 ID", detail.orderId)
                DetailLine("订单原价", detail.originPrice)
                DetailLine("花费小票", detail.ticketCost)
                DetailLine("积分抵扣", detail.integralCost)
                if (detail.otherPromotions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("其他优惠", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    detail.otherPromotions.forEach { promotion ->
                        DetailLine(
                            label = "类型 ${promotion.promotionType ?: "-"}",
                            value = promotion.discountAmount ?: "-",
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了")
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}


@Composable
private fun ControlScreen(
    state: com.example.devicecontrol.ui.AppUiState,
    vm: AppViewModel,
) {
    val context = LocalContext.current
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "未授予相机权限，可使用手动输入链接", Toast.LENGTH_LONG).show()
        }
        vm.openScanner()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    PageTitle("轻饮", "点击设备开始使用")
                }
                IconButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            vm.openScanner()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !state.unlocking && !state.scanningLookup,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Outlined.CropFree,
                        contentDescription = "扫码添加设备",
                    )
                }
                IconButton(
                    onClick = vm::refreshDevices,
                    enabled = state.hasToken && !state.loadingDevices,
                ) {
                    androidx.compose.material3.Icon(Icons.Outlined.Refresh, contentDescription = "刷新设备")
                }
            }
        }
        when {
            !state.hasToken && state.devices.isEmpty() -> item { EmptyPanel("请先登录", "前往“我的”页面登录后，即可同步或扫码添加设备。") }
            state.loadingDevices && state.devices.isEmpty() -> item { LoadingPanel("正在查询设备") }
            state.devices.isEmpty() -> item { EmptyPanel("暂无可用设备", "点击右上角扫码添加设备，或使用过的设备会显示在这里。") }
            else -> items(state.devices) { device ->
                val key = device.goodsId ?: device.id
                val canRemove = key != null && key in state.savedDeviceIds
                DeviceCard(
                    name = device.goodsName.ifBlank { "未命名设备" },
                    status = state.unlockStatus,
                    enabled = !state.unlocking,
                    onClick = { vm.requestUnlock(device) },
                    onViewDetails = { vm.openDeviceDetails(device) },
                    onRemove = if (canRemove) {
                        { vm.requestRemoveDevice(device) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun MeScreen(
    state: com.example.devicecontrol.ui.AppUiState,
    vm: AppViewModel,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                PageTitle("我的", if (state.hasToken) "当前账号" else "登录后管理账号和订单")
            }
            IconButton(onClick = { openProjectHome(context) }) {
                androidx.compose.material3.Icon(
                    painter = painterResource(R.drawable.ic_github),
                    contentDescription = "打开 GitHub 仓库",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("账号登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("登录后同步设备与账户资产", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.phone,
                        onValueChange = vm::updatePhone,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("手机号") },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = RoundedCornerShape(16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = state.code,
                            onValueChange = vm::updateCode,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("短信验证码") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(16.dp),
                        )
                        FilledTonalButton(
                            onClick = vm::sendCode,
                            modifier = Modifier.padding(top = 6.dp),
                            enabled = !state.sendingCode && state.sendCodeCooldownSeconds == 0,
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Text(
                                text = when {
                                    state.sendingCode -> "发送中…"
                                    state.sendCodeCooldownSeconds > 0 -> "${state.sendCodeCooldownSeconds}s后重发"
                                    else -> "发送验证码"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                Button(
                    onClick = vm::login,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.loggingIn,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Text(if (state.loggingIn) "正在登录…" else "登录")
                }
            }
        }

        Text("快捷操作", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(
                modifier = Modifier.weight(1f),
                icon = { androidx.compose.material3.Icon(Icons.Outlined.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "当前 Token",
                subtitle = "查看登录凭证",
                onClick = vm::showCurrentToken,
            )
            ActionTile(
                modifier = Modifier.weight(1f),
                icon = { androidx.compose.material3.Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "历史订单",
                subtitle = "查看使用记录",
                onClick = vm::showOrderHistory,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("账户资产", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::refreshBalance, enabled = state.hasToken && !state.loadingBalance) {
                        androidx.compose.material3.Icon(Icons.Outlined.Refresh, contentDescription = "刷新资产")
                    }
                }
                when {
                    !state.hasToken -> Text("登录后查看小票、积分和抵扣金额", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.loadingBalance -> LoadingPanel("正在查询账户资产")
                    state.balance != null -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssetValue(Modifier.weight(1f), "小票", state.balance.ticketText, Icons.Outlined.ConfirmationNumber)
                            AssetValue(Modifier.weight(1f), "积分", state.balance.pointsText, Icons.Outlined.Stars)
                            AssetValue(Modifier.weight(1f), "积分抵扣", state.balance.integralAmount ?: "-", Icons.Outlined.Code)
                        }
                    }
                    else -> Text("暂无资产信息", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun OrderHistoryDialog(
    orders: List<OrderHistoryItem>,
    onDismiss: () -> Unit,
    onOpenOrder: (OrderHistoryItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("历史订单") },
        text = {
            if (orders.isEmpty()) {
                Text("暂无历史订单", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn {
                    items(orders) { item ->
                        OrderHistoryRow(item = item, onClick = { onOpenOrder(item) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}
@Composable
private fun OrderHistoryRow(item: OrderHistoryItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(item.goodsName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "订单：${item.orderNo}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "原价：${item.originPrice}  小票：${item.ticketCost}  积分：${item.integralCost}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeviceCard(
    name: String,
    status: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.Home,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(status ?: "点击卡片开始使用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    androidx.compose.material3.Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text("查看详情") },
                        onClick = {
                            menuExpanded = false
                            onViewDetails()
                        },
                    )
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("移除设备") },
                            onClick = {
                                menuExpanded = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoadingPanel(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AssetValue(
    modifier: Modifier,
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
