# 极核投屏 · ZEEHO Cast (Android)

面向极核 AE5i（春风系）仪表的第三方投屏工具，支持**熄屏投屏**。

## 功能

- **熄屏投屏**：MediaProjection + 前台服务，锁屏后继续推流
- **连接方式全支持**：
  - 自动发现：mDNS 查询 `_EasyConn._tcp`（端口 1807）
  - 扫码连接：扫描仪表二维码
  - 手动连接：IP + 端口
- **H.264 硬编码**：MediaCodec，默认 720x1280 / 4Mbps / 30fps（可调）
- **协议日志导出**：全量 HEX 收发日志，便于协议校准
- 高级暗色 UI（碳黑 + 极核橙）

## 技术背景

极核 MotoPlay 投屏为武汉卡比特（亿连 EasyConnection）EC-SDK 白标方案。本应用按逆向结论实现 EC 协议层：
TCP 连接仪表（默认 `192.168.49.1:1807`）→ EC_Ping 握手 → HELLO → 打开镜像通道 → 推 H.264 帧。

> 说明：帧头字节级布局（magic/命令ID）为逆向推断模板，如实测不通，请导出 HEX 日志（`导出日志`）对照官方抓包后微调 `ECProtocol.kt` 中的常量。

## 免责声明

本工具仅供个人维护与调试用途，请勿用于非法用途。使用本工具产生的任何后果由使用者自行承担。

## 构建

```bash
gradle assembleDebug
```

或使用仓库内 `.github/workflows/build.yml` 由 GitHub Actions 自动构建 APK（产物在 Actions Artifacts 下载）。
