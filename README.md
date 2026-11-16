# 轻饮（PGLife_Lite）

[![GitHub](https://img.shields.io/badge/GitHub-SleexyDev%2FPGLife__Lite-1A1A1A)](https://github.com/SleexyDev/PGLife_Lite)

一个极简风格的 Android 轻量化设备控制客户端（Jetpack Compose + Material 3），面向扫码添加与快速开启饮水设备的校园场景。

参考项目：[qiekj-android](https://github.com/wzs0512/qiekj-android)

## 主要功能

- **短信验证码登录与本地持久化**
  - 支持手机号获取短信验证码并登录。
  - 使用 `EncryptedSharedPreferences` 在本地加密保存登录 Token 与上次登录的手机号，重启应用自动回填手机号与登录态。
- **离线相机扫码识别与手动加设备**
  - 内置 CameraX + ZXing 离线二维码识别引擎（无需网络依赖的本地解码），支持点击屏幕对焦、手电筒开关以及「手动添加链接 / SN」。
  - 针对蓝色背光、像素点阵式 LCD 屏二维码做了灰度、红通道提取与 2D 腐蚀预处理优化，可直接识别设备屏幕二维码并解析 `https://h5.qiekj.com/skip?SN=...` 等链接与设备号。
  - 自动查询并补全设备信息（`goodsId`、`SN`、`IMEI`、`SKU`、品类等），保存到本地并合并云端最近使用设备列表。
- **设备控制与管理**
  - 点击设备卡片弹出二次确认弹窗后开启设备，自动记录订单并在使用页展示订单详情（订单号、原价、小票、积分抵扣与其他优惠）。
  - 点击卡片右侧 `⋮` 更多菜单可「查看详情」（点击设备链接区域即可一键复制链接）或「移除设备」（带二次确认弹窗，仅对本地扫码添加的设备展示移除操作）。
- **账户与订单记录**
  - 在「我的」页面查看账户小票、积分与积分抵扣额度，支持查看当前 Token、本地历史订单记录与订单详情，以及右上角快速跳转项目仓库。

## 技术栈与项目结构

- **开发环境**：Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.10.2 · `compileSdk 35` / `minSdk 26` / `targetSdk 35`
- **主要依赖**：Jetpack Compose (BOM `2024.12.01`) / Material 3、CameraX `1.4.1`、ZXing Core `3.5.3`、Retrofit `2.11.0` / OkHttp `4.12.0` / Moshi `1.15.1`、AndroidX Security Crypto (`1.1.0-alpha06`)

```text
app/src/main/java/com/example/devicecontrol/
├── MainActivity.kt              # 主界面、弹窗与交互组件（设备控制 / 我的）
├── data/
│   ├── ApiConfig.kt             # 接口基础配置与常量
│   ├── AppRepository.kt         # 登录、扫码查询、设备控制与资产查询仓库
│   ├── DeviceApi.kt             # Retrofit 接口定义
│   ├── EmptyDataJsonAdapter.kt  # 空 data 字段兼容适配器
│   ├── HeaderInterceptor.kt     # 请求头拦截器
│   ├── LenientStringJsonAdapter.kt
│   ├── Models.kt                # 请求/响应与本地数据模型
│   ├── OrderHistoryStore.kt     # 本地订单历史持久化
│   ├── SavedDeviceStore.kt      # 本地扫码设备持久化
│   └── TokenStore.kt            # Token 与手机号加密存储
└── ui/
    ├── AppViewModel.kt          # 页面状态与业务逻辑（AppUiState）
    ├── QrCodeDecoder.kt         # LCD 点阵屏增强与 ZXing 离线解码器
    ├── QrScannerDialog.kt       # CameraX 扫码界面与手动输入弹窗
    └── theme/Theme.kt           # Material 3 主题配色
```

## 免责声明

本项目仅供学习与个人日常便捷使用交流，请勿用于任何违反服务协议或法律法规的用途。
