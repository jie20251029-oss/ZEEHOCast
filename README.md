# ZEEHOCast · 极核投屏（Android + iOS）

[![Build](https://github.com/jie20251029-oss/ZEEHOCast/actions/workflows/build.yml/badge.svg)](https://github.com/jie20251029-oss/ZEEHOCast/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/jie20251029-oss/ZEEHOCast?label=release)](https://github.com/jie20251029-oss/ZEEHOCast/releases)

面向**极核 AE5i（春风系）**仪表 WiFi 投屏的第三方双端客户端，Android 与 iOS 各一套，核心目标是**熄屏投屏**。

> 本项目按逆向结论重新实现仪表侧的 EC 协议（亿连 EasyConnection / 武汉卡比特白标方案），
> 与极核（ZEEHO）、春风动力、卡比特/亿连均**无任何关联**，详见文末[免责声明](#免责声明)。

---

## 目录结构

```
ZEEHOCast/
├── .github/workflows/build.yml      # 根 CI：一次构建 Android APK + iOS IPA
├── ZEEHOCast_Android/               # Android 端（Kotlin + ViewBinding）
│   ├── app/src/main/java/com/zeeho/cast/
│   │   ├── CastService.kt           # 前台服务：MediaProjection 录屏 + 推流
│   │   ├── ScreenEncoder.kt         # MediaCodec H.264 硬编码（AVCC → AnnexB）
│   │   ├── MainActivity.kt          # 主界面（发现/扫码/手动）
│   │   ├── ec/ECProtocol.kt         # EC 协议常量、帧封装、日志总线
│   │   ├── ec/ECConnection.kt       # TCP 连接、握手状态机、帧收发
│   │   ├── ec/ECDiscovery.kt        # mDNS _EasyConn._tcp 发现
│   │   └── ui/                      # CastActivity / QRScanActivity
│   └── gradlew                      # Gradle 8.2 wrapper（CI 用这个，不用系统 gradle）
└── ZEEHOCast_iOS/                   # iOS 端（SwiftUI + ReplayKit）
    ├── project.yml                  # xcodegen 工程定义
    ├── App/Sources/                 # 主 App：ContentView / CastView / EC 协议层
    └── BroadcastExtension/          # ZEEHOCastBroadcast：ReplayKit 广播扩展
```

## 功能

| | Android | iOS |
|---|---|---|
| 熄屏投屏 | MediaProjection + 前台服务 | ReplayKit 广播扩展（独立进程录制，锁屏继续） |
| 自动发现 | mDNS `_EasyConn._tcp` | NWBrowser + NetService 解析 |
| 扫码连接 | CameraX + ZXing | AVFoundation |
| 手动连接 | IP + 端口 | IP + 端口 |
| 视频编码 | MediaCodec H.264（默认 720×1280 / 4 Mbps / 30 fps） | VideoToolbox 直通已编码帧 |
| 协议日志 | 全量 HEX 收发日志，可导出 | 同左 |

## 工作原理

```
Android: MediaProjection ──Surface──▶ MediaCodec(H.264) ──┐
                                                          ├─▶ EC 帧封装 ──TCP 1807──▶ 仪表
iOS:  ReplayKit 扩展 ──CMSampleBuffer──▶ localhost:53001 ─┘        (192.168.49.1)
                                              (主 App 接收)
```

握手流程：TCP 连接 → `EC_Ping` → 等 `PONG` → `HELLO` → 打开镜像通道 → 持续推 H.264 帧。

## 快速开始

1. 手机连上仪表的 WiFi AP（投屏网段默认 `192.168.49.1`）。
2. 从 [Releases](https://github.com/jie20251029-oss/ZEEHOCast/releases) 下载：
   - **Android**：`ZEEHOCast-*-debug.apk` 可直接安装。
   - **iOS**：见下方[安装与自签](#ios-安装与自签)。
3. 打开 App → 自动发现 / 扫仪表二维码 / 手动填 IP → 开始投屏。

## iOS 安装与自签

iOS 产物有两个版本，**按你的设备选一个**：

| 文件 | 适用 | 说明 |
|---|---|---|
| `ZEEHOCast-1.0.0-unsigned.ipa` | 爱思助手 / Sideloadly / AltStore 等自签工具 | 工具会用你的 Apple ID 重新签名，免费账号 7 天需续签 |
| `ZEEHOCast-1.0.0-adhoc.ipa` | TrollStore、越狱设备 | 已做 ad-hoc 签名（`codesign -s -`），TrollStore 可直接装 |

自签时**两个 Bundle ID 都要签**（工具会自动处理，若报错请检查）：

- 主 App：`com.zeeho.cast`
- 广播扩展：`com.zeeho.cast.broadcast`

**自签后打不开 / 装不上的常见原因**（本项目已修，供排查参考）：

1. **扩展 Info.plist 缺 `NSExtension`** —— 这是最常见的坑。ReplayKit 广播扩展必须有
   `NSExtensionPointIdentifier = com.apple.broadcast-services-upload`，否则系统广播选择器里
   看不到它，重签安装时也会被判为「无效扩展」直接失败。
   本项目早期版本因为 `xcodegen` 只用 `info.path` 生成模板 plist、覆盖了手写文件而丢了这个键，
   现在所有 plist 键都写在 [project.yml](ZEEHOCast_iOS/project.yml) 的 `info.properties` 里。
2. **缺 `NSLocalNetworkUsageDescription` / `NSBonjourServices`** —— iOS 14+ 访问局域网需要授权，
   否则 mDNS 自动发现永远为空（不报错，只是搜不到设备）。
3. **缺 `UIBackgroundModes: audio`** —— 熄屏后进程会被系统挂起，投屏中断。
4. 免费 Apple ID 签名后**只能维持 7 天**，到期需重新签名；TrollStore 安装则不受此限制（取决于系统版本）。

## 构建

**Android**（JDK 17 + Android SDK 34，Gradle 由 wrapper 固定为 8.2）：

```bash
cd ZEEHOCast_Android
./gradlew assembleDebug assembleRelease
```

**iOS**（macOS + Xcode + xcodegen）：

```bash
cd ZEEHOCast_iOS
brew install xcodegen && xcodegen generate     # Info.plist 与 .xcodeproj 都由它生成
xcodebuild -project ZEEHOCast.xcodeproj -scheme ZEEHOCast \
  -configuration Release -sdk iphoneos -derivedDataPath build \
  CODE_SIGNING_ALLOWED=NO build

# 未签名 IPA
mkdir -p Payload && cp -R build/Build/Products/Release-iphoneos/ZEEHOCast.app Payload/ \
  && zip -r ZEEHOCast-unsigned.ipa Payload && rm -rf Payload

# ad-hoc 签名 IPA（先签扩展再签主 App）
APP=build/Build/Products/Release-iphoneos/ZEEHOCast.app
codesign --force --sign - --timestamp=none "$APP/PlugIns/ZEEHOCastBroadcast.appex"
codesign --force --sign - --timestamp=none "$APP"
mkdir -p Payload && cp -R "$APP" Payload/ && zip -r ZEEHOCast-adhoc.ipa Payload && rm -rf Payload
```

> **改 Info.plist 请改 `project.yml`**，不要直接改 `App/Info.plist` / `BroadcastExtension/Info.plist`
> —— 那两个文件由 `xcodegen generate` 生成并覆盖。

**CI**：推到 `main` 后根 workflow 自动双端构建，产物在 Actions 的 Artifacts；打 tag 后可在 Releases 发布。

> 构建环境速记：AGP 8.1.0 只支持 Gradle 8.x（Gradle 9 会因 API 移除直接失败），
> 所以务必用仓库内的 `gradlew`，不要用 runner/本机自带的 `gradle`。

## 已知限制

- **协议字段是逆向推断的**：帧头字节级布局（magic / 命令 ID / 长度域）依据 `cfmoto-client-ios 3.0.5` 与 `libECSDK.so` 的观察得出，属于合理模板而非官方文档。若实车不通，请用 App 内「导出日志」抓 HEX，对照微调 `ECProtocol.kt` / `ECProtocol.swift` 里的常量。
- **仪表固件差异**：不同年份/批次固件的命令 ID、mDNS 服务名可能不同。
- **产物未签名**：CI 用 `CODE_SIGNING_ALLOWED=NO` 出包；iOS 需自签或用 TrollStore，Android release 包为 unsigned。
- **仅在仪表 WiFi AP 环境下可用**，不经过云端，也不支持远程投屏。

## 免责声明

**1. 非官方项目。** 本项目为个人学习与自用调试的第三方实现，与极核（ZEEHO）、春风动力（CFMOTO）、武汉卡比特/亿连（EasyConnection）及其关联公司**没有任何隶属、授权、合作或背书关系**。文中出现的产品名、商标、服务名归各自权利人所有，仅用于说明兼容对象。

**2. 技术来源。** 协议细节来自对公开可获取的客户端样本与运行库的观察分析，用于实现**自有设备之间的互操作**。项目不包含、也不分发任何官方二进制、密钥、证书或固件。

**3. 使用风险自负。** 本软件按「现状」（AS IS）提供，**不附带任何明示或默示的担保**，包括但不限于适销性、特定用途适用性与不侵权担保。因使用或无法使用本软件导致的任何直接、间接、附带、特殊或后果性损害（含仪表显示异常、连接中断、数据丢失、设备损坏、保修失效等），作者与贡献者**概不负责**。

**4. 合规使用。** 请自行确认你的使用行为符合当地法律法规、车辆保修条款以及仪表厂商的服务条款。**禁止**将本项目用于绕过付费/版权保护、商业分发、批量改装、监视他人或任何非法用途。

**5. 安全提醒。** 投屏会捕捉并传输屏幕内容，请注意隐私；**严禁在驾驶过程中操作手机**，分心驾驶可能导致严重事故。任何刷机、改固件操作均有设备变砖风险，且不在本项目讨论范围内。

**6. 权利主张。** 若相关权利人认为本项目内容侵犯其合法权益，请通过 Issue 或邮件联系，我们会及时处理（下架相关内容或调整实现）。

### Disclaimer (English)

This is an unofficial, non-commercial interoperability project for personal use. It is **not affiliated with, endorsed by, or sponsored by** ZEEHO, CFMOTO, or Cobit/EasyConnection. Protocol details were derived from observation of publicly accessible binaries for interoperating with devices the user owns; no official binaries, keys, or firmware are bundled or redistributed. The software is provided **"AS IS", without warranty of any kind**, and the authors accept **no liability** for any damages arising from its use. You are responsible for complying with applicable laws, warranty terms, and the manufacturer's terms of service. Do not operate a phone while driving.

## 许可

本仓库目前**未指定开源许可证**（默认保留所有权利）。如果你希望复用、二次分发或商用，请先开 Issue 沟通；若需要，可补上 MIT / Apache-2.0 等许可证。

## 致谢

- 协议线索来自对 `cfmoto-client-ios` 与 `libECSDK.so` 的公开观察；
- Android 端 UI 基于 Material 3，iOS 端基于 SwiftUI + ReplayKit。
