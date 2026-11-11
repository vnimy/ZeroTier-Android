<h1 align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="ZeroTier" width="120">
  <br>
  ZeroTier
</h1>

<p align="center">非官方 ZeroTier Android 客户端</p>

<p align="center">
  <a href="https://github.com/vnimy/ZeroTier-Android">GitHub 仓库</a> ·
  <a href="https://zt.vnimy.com/app/zerotier.apk">下载 APK</a> ·
  <a href="https://github.com/vnimy/ZeroTier-Android/issues">问题反馈</a>
</p>

<p align="center">
  <img src="screenshots/main_cn.png" alt="网络列表" width="150"/>
  <img src="screenshots/peers_cn.png" alt="节点列表" width="150"/>
  <img src="screenshots/moons_cn.png" alt="Moon 配置" width="150"/>
</p>

> 本项目是非官方客户端，与 ZeroTier, Inc. 没有隶属关系。ZeroTier 名称与图标归其权利人所有。

## 简介

这是基于 [kaaass/ZerotierFix](https://github.com/kaaass/ZerotierFix) 维护的 ZeroTier Android 客户端，底层使用 ZeroTierOne 的 JNI SDK。当前版本为 **2.0.2**，包名已迁移为 `com.vnimy.zerotier`。

本项目主要面向自建 Planet / Moon 的使用场景，保留了官方的网络连接能力，并补充了适合日常使用的界面与快捷操作。

## 功能

- 加入、离开 ZeroTier 网络，查看网络详情
- 查看当前网络的 Peer（节点）列表
- 支持自建 Moon，并持久化 Moon 配置
- 支持通过文件和 URL 导入自定义 Planet
- 支持扫码导入 Planet 配置
- 支持扫描网络二维码，自动识别并填入 Network ID
- 快捷设置磁贴：Android 7.0+ 下拉面板一键连接/断开，长按打开 App
- 通知栏显示连接状态，可直接断开
- 可配置移动数据、IPv6 等网络选项
- 官方 ZeroTier 橙色主题（#fac073），顶部栏与开关配色与官方客户端一致
- 中英文界面
- 应用内检查更新（读取当前 Planet 服务器的 `version.json`）

## 下载

最新版本：**2.0.2 (versionCode 20)**

- APK：<https://zt.vnimy.com/app/zerotier.apk>
- 更新检查：<https://zt.vnimy.com/version.json>

### 从 1.x 升级

2.0 系列的包名从 `net.kaaass.zerotierfix` 改为 `com.vnimy.zerotier`，**不能直接覆盖安装**。

请先卸载旧版，再安装 2.0.2；网络、Moon、Planet 等配置需要重新添加。全新安装没有历史数据迁移负担。

## 使用

1. 安装并打开 App，首次连接时按系统提示授予 VPN 权限。
2. 点击右下角 `+`，输入 16 位 Network ID 加入网络，也可以点击“扫描二维码”扫描包含 Network ID 的二维码自动填入，然后打开网络开关连接。
3. 在设置中配置自建 Planet、Moon、移动数据、IPv6 等选项。
4. 快捷设置：下拉通知栏 → 点击编辑（铅笔）→ 把 **ZeroTier** 拖入面板。点按连接/断开，长按打开 App。
5. 连接后可在通知栏查看状态，或直接点击“断开”。

## 系统要求

| 项目 | 要求 |
| --- | --- |
| Android | 5.0（API 21）及以上 |
| 快捷设置磁贴 | 7.0（API 24）及以上 |
| CPU 架构 | arm64-v8a、armeabi-v7a |
| 权限 | VPN；Android 13+ 建议授予通知权限 |

当前 APK 只包含 ARM 架构，不包含 x86/x86_64。

## 构建

### 环境要求

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- NDK 28.2.13676358（r28c）
- CMake 3.22.1
- Git（需要检出子模块）

Gradle Wrapper 使用 8.7，AGP 8.6.1。

### local.properties

```properties
sdk.dir=/path/to/android-sdk
ndk.dir=/path/to/android-sdk/ndk/android-ndk-r28c
cmake.dir=/path/to/cmake-3.22.1
```

说明：SDK 中的 NDK 目录名是 `android-ndk-r28c`，与 AGP 期望的版本号目录不一致，因此这里显式使用 `ndk.dir`。该配置在新版 AGP 中已标记为弃用，但当前构建仍然依赖它。

### 编译

```bash
git clone --recursive https://github.com/vnimy/ZeroTier-Android.git
cd ZeroTier-Android

# 构建前应用 JNI 适配补丁（适配 ZeroTierOne 1.16.2）
git -C externals/core apply ../../tools/core-patches/0001-jni-1.16.2.patch

./gradlew :app:assembleDebug

# 构建完成后撤销补丁，保持子模块干净
git -C externals/core apply -R ../../tools/core-patches/0001-jni-1.16.2.patch
```

Windows 下把最后一步换成：

```powershell
.\gradlew.bat :app:assembleDebug
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如果使用配套的 `zt-stack/tools/build-client.sh` 构建，脚本会自动完成补丁应用与撤销，并按 `ZeroTier-<core 版本>-debug.apk` 命名产物。

### 签名

Debug 构建使用本机自动生成的 debug keystore。正式分发时请配置自己的 release keystore，并确保后续版本使用同一签名，否则无法覆盖升级。

## 项目结构

```text
ZeroTier-Android/
├── app/                    Android 应用（com.vnimy.zerotier）
├── core/                   JNI 封装库（com.zerotier.sdk）
├── externals/core/         ZeroTierOne 子模块（1.16.2）
├── tools/core-patches/     JNI 构建时适配补丁
├── screenshots/            应用截图
├── gradle/                 Gradle Wrapper
├── gradlew / gradlew.bat
└── README.md
```

## 更新服务

App 会向当前配置的 Planet 服务器请求 `version.json`，并据此提示更新。当前发布服务器使用以下文件：

```json
{
  "versionCode": 20,
  "versionName": "2.0.2",
  "url": "https://zt.vnimy.com/app/zerotier.apk",
  "notes": "全新应用：包名变更为 com.vnimy.zerotier，请卸载旧版后安装。"
}
```

`versionCode` 必须是整数，且新版本要大于已安装版本。自建服务器时，把 `version.json` 和 APK 放到 Planet 文件服务的对应目录即可；文件名可以自定义，只要 `url` 指向实际地址。

## 与上游的关系

- 本项目基于 [kaaass/ZerotierFix](https://github.com/kaaass/ZerotierFix)。
- 原始 Android 客户端代码来自对官方客户端的逆向工程，原始作者为 Grant Limberg（glimberg@gmail.com）。
- JNI SDK 位于子模块 `externals/core`，当前锁定 ZeroTierOne 1.16.2，并通过 `tools/core-patches` 在构建时应用 JNI 适配。
- 2.0 起，应用包名由 `net.kaaass.zerotierfix` 迁移为 `com.vnimy.zerotier`，显示名称统一为 ZeroTier。

## 许可证与商标

- 本项目沿用上游的 **GNU General Public License v2.0**，详见 [LICENSE](LICENSE)。二次分发时请保留许可证与版权声明。
- ZeroTier 名称、图标及相关标识归 ZeroTier, Inc. 及原项目作者所有。
- 本项目为非官方客户端，与 ZeroTier, Inc. 没有隶属关系。

## 致谢

- [ZeroTier, Inc.](https://www.zerotier.com/) 与 [ZeroTierOne](https://github.com/zerotier/ZeroTierOne)
- [kaaass/ZerotierFix](https://github.com/kaaass/ZerotierFix)
- Grant Limberg 及所有上游贡献者

## 反馈

欢迎通过 [GitHub Issues](https://github.com/vnimy/ZeroTier-Android/issues) 反馈问题。提交日志时请勿包含网络密钥、私钥或其它敏感信息。
