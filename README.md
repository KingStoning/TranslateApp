# 划词翻译 - FloatTranslate

一款现代化的 Android 划词翻译应用，**选中文字 → 点击悬浮球 → 秒出翻译结果**。基于百度翻译 API，使用 Jetpack Compose 构建。

![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin)
![Compose](https://img.shields.io/badge/Compose-BOM%202024.12-4285F4?logo=jetpackcompose)
![API](https://img.shields.io/badge/API-百度翻译-blue)
![License](https://img.shields.io/badge/License-Apache%202.0-green)

---

## ✨ 功能特色

- 🎯 **悬浮球翻译** — 屏幕上的蓝色悬浮球，点击即可翻译剪贴板内容
- 📋 **划词翻译** — 在任意应用中选中文本，通过系统菜单的"翻译"选项直接翻译
- 🧩 **无障碍增强** — 可选开启无障碍服务，选中文本后无需复制，直接点击悬浮球翻译
- 🌐 **百度翻译 API** — 国内直连，速度快，每月 100 万字符免费额度
- 🎨 **Material Design 3** — 现代化界面，支持动态取色（Android 12+）
- 🌙 **深色模式** — 自动跟随系统深色主题
- 📱 **轻量快速** — 仅 21MB，Kotlin + Jetpack Compose 构建


## 🚀 快速开始

### 下载安装

从 [Releases 页面](https://github.com/shenzh21/TranslateApp/releases) 下载最新的 APK 安装包。

### 获取百度翻译 API 密钥

1. 访问 [百度翻译开放平台](https://fanyi-api.baidu.com/)
2. 用百度账号登录 → 开通「通用翻译 API」
3. 完成**个人实名认证** → 切换到**高级版**（每月 100 万字符免费）
4. 记录你的 **APP ID** 和 **密钥**

### 初次使用

1. 安装 APK 后打开应用
2. 进入**设置** → 在「百度翻译 API 配置」中输入你的 APP ID 和密钥 → 点击保存
3. 开启**「显示悬浮球」**
4. 同意通知权限（用于保持悬浮球后台运行）

### 使用方式

#### 方式一：悬浮球翻译（推荐）

```
① 在任意应用中选中文字 → ② 点击「复制」
③ 点击屏幕上的蓝色悬浮球 → ④ 翻译结果弹出
```

#### 方式二：划词翻译

```
① 在任意应用中选中文字
② 在弹出的工具栏中选择「翻译」
③ 翻译结果直接显示
```

> 部分系统（如 ColorOS / OxygenOS）会把「翻译」收在工具栏的「更多 ⋮」里。

#### 方式三：分享翻译

```
① 选中文字后点「分享」，或在 App 的「更多 → 分享/发送」中
② 在分享面板里选择「翻译」
③ 翻译结果直接显示
```

#### 方式四：无障碍模式（进阶）

在系统设置 → 无障碍 → 划词翻译 中开启服务后，**无需复制**，选中文字直接点击悬浮球即可翻译。

## 🛠️ 技术栈

| 组件 | 技术 |
|------|------|
| 编程语言 | [Kotlin](https://kotlinlang.org/) 2.1.0 |
| UI 框架 | [Jetpack Compose](https://developer.android.com/jetpack/compose) + Material 3 |
| 架构 | MVVM (ViewModel + Repository) |
| 网络请求 | [Retrofit 2](https://square.github.io/retrofit/) + OkHttp 4 |
| 数据解析 | Gson |
| 异步 | Kotlin Coroutines + Flow |
| 编译 SDK | Android 35 |
| 最低支持 | Android 7.0 (API 24) |

## 📁 项目结构

```
app/src/main/java/com/translate/app/
├── App.kt                          # Application 类
├── MainActivity.kt                 # 主界面 + 权限申请
├── ProcessTextActivity.kt          # 翻译浮窗 Activity
├── SettingsActivity.kt             # 设置界面
├── data/
│   ├── CredentialManager.kt        # API 凭据管理
│   ├── api/
│   │   ├── ApiClient.kt            # Retrofit 客户端
│   │   ├── BaiduTranslateService.kt # 百度翻译 API 接口
│   │   └── SignUtil.kt             # MD5 签名工具
│   ├── model/TranslationResult.kt  # 数据模型
│   └── repository/TranslateRepository.kt  # 翻译仓库
├── service/
│   ├── FloatingBubbleService.kt    # ⭐ 悬浮球服务
│   ├── ClipboardMonitorService.kt  # 剪贴板监听服务
│   ├── TranslateAccessibilityService.kt # 无障碍服务
│   └── BootReceiver.kt             # 开机自启
└── ui/
    ├── main/
    │   ├── MainScreen.kt           # 主界面 Compose UI
    │   └── MainViewModel.kt        # 主界面 ViewModel
    ├── overlay/
    │   ├── FloatTranslationManager.kt    # 悬浮窗管理器
    │   └── FloatTranslationComposable.kt # 悬浮窗 Compose UI
    └── theme/                      # Material 3 主题
```

## 🔧 自行构建

```bash
# 克隆项目
git clone https://github.com/shenzh21/TranslateApp.git
cd TranslateApp

# 使用 Gradle 构建 Debug APK
./gradlew assembleDebug

# APK 生成在 app/build/outputs/apk/debug/app-debug.apk
```

需要安装 [Android SDK](https://developer.android.com/studio) 33+ 和 JDK 17。

## 📄 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。

---

> **免责声明**：本应用仅调用百度翻译开放平台 API，不收集任何用户数据。翻译内容请遵守当地法律法规。
