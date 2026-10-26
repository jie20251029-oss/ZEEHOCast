# 极核投屏 · ZEEHO Cast (iOS)

面向极核 AE5i（春风系）仪表的第三方投屏工具，支持**熄屏投屏**（iOS）。

## 功能

- **熄屏投屏**：ReplayKit 广播扩展（RPBroadcastSampleHandler）在独立进程录制屏幕，锁屏后继续；帧经本地 socket 回传主 App 推流
- **连接方式全支持**：
  - 自动发现：mDNS 查询 `_EasyConn._tcp`（NWBrowser，端口 1807）
  - 扫码连接：AVFoundation 二维码识别
  - 手动连接：IP + 端口
- **H.264 直通推流**：扩展输出的已编码 H.264 帧直接封装 EC 帧（VideoToolbox 硬件编码，低开销）
- **协议日志导出**：全量 HEX 收发日志
- SwiftUI 高级暗色 UI（碳黑 + 极核橙）

## 技术架构

```
ReplayKit Broadcast Extension (ZEEHOCastBroadcast)
        │  CMSampleBuffer (H.264)
        ▼  localhost TCP :53001（帧：[4B长度][1B关键帧][payload]）
主 App ZEEHOCast
        │  EC 协议封装（magic+cmd+len）
        ▼  TCP 1807（仪表 WiFi，192.168.49.1）
极核 AE5i 仪表（亿连 EC-SDK 接收端）
```

免签设计：扩展与主 App 通过 **localhost TCP socket** 通信，不依赖 App Group entitlement，
TrollStore / 侧载环境同样可用。

## 构建

```bash
brew install xcodegen
xcodegen generate
xcodebuild -project ZEEHOCast.xcodeproj -scheme ZEEHOCast -configuration Release -sdk iphoneos CODE_SIGNING_ALLOWED=NO build
# 打包
mkdir Payload && cp -R build/Build/Products/Release-iphoneos/ZEEHOCast.app Payload/ && zip -r ZEEHOCast.ipa Payload
```

或推送仓库后由 `.github/workflows/build.yml`（macOS runner）自动构建，产物在 Actions Artifacts 下载。

## 免责声明

本工具仅供个人维护与调试用途，请勿用于非法用途。使用本工具产生的任何后果由使用者自行承担。
