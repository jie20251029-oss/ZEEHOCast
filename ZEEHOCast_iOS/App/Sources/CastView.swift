import SwiftUI
import ReplayKit

/// 投屏控制页：连接 EC → 启动广播录屏 → 扩展帧经本地socket回传 → 推流
struct CastView: View {
    @Environment(\.presentationMode) var presentationMode
    @State private var statusText = "正在建立投屏通道…"
    @State private var isCasting = false
    @State private var host: String
    @State private var port: Int

    private var bridge = BroadcastBridge.shared

    init() {
        _host = State(initialValue: ECConstants.defaultHost)
        _port = State(initialValue: ECConstants.defaultPort)
    }

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("投屏")
                        .font(.system(size: 26, weight: .bold, design: .rounded))
                        .foregroundColor(Theme.textPrimary)
                    Text(statusText)
                        .font(.system(size: 14))
                        .foregroundColor(isCasting ? Theme.success : Theme.accent)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.top, 20)

                VStack(spacing: 18) {
                    ZStack {
                        Circle()
                            .fill(Theme.accent.opacity(0.1))
                            .frame(width: 64, height: 64)
                        Circle()
                            .fill(Theme.accent)
                            .frame(width: 16, height: 16)
                    }
                    Text(isCasting ? "镜像传输中" : "镜像传输待启动")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundColor(Theme.textPrimary)
                    Text("屏幕内容将通过仪表 WiFi 实时投送到仪表显示屏\n锁屏后投屏继续（熄屏投屏）")
                        .font(.system(size: 13))
                        .foregroundColor(Theme.textSecondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(4)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
                .cardStyle()
                .padding(.horizontal, 20)
                .padding(.top, 24)

                Spacer()

                if isCasting {
                    Button(action: stopCast) {
                        Text("断开投屏")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(Theme.danger)
                            .frame(maxWidth: .infinity)
                            .frame(height: 54)
                            .background(Theme.danger.opacity(0.08))
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.danger.opacity(0.4), lineWidth: 1))
                            .cornerRadius(14)
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 12)
                }

                Button("导出日志") { exportLog() }
                    .font(.system(size: 13))
                    .foregroundColor(Theme.textSecondary)
                    .padding(.bottom, 20)
            }
        }
        .onAppear {
            startCast()
        }
    }

    private func startCast() {
        guard !isCasting else { return }
        statusText = "正在建立投屏通道…"

        // 1. 启动本地socket接收（扩展回传帧）
        bridge.start { data, isKey in
            ECConnectionManager.shared.sendVideoFrame(data, isKeyFrame: isKey)
        }

        // 2. 连接 EC + 开镜像
        let conn = ECConnection(host: host, port: port)
        ECConnectionManager.shared.conn = conn
        conn.onState = { s in
            DispatchQueue.main.async {
                switch s {
                case .ready:
                    statusText = "连接成功，启动录屏…"
                    conn.openMirror(width: 720, height: 1280, bitrate: 4_000_000, fps: 30)
                    conn.sendVideoInfo(width: 720, height: 1280, bitrate: 4_000_000)
                    conn.startMirror()
                case .streaming:
                    statusText = "投屏中"
                    isCasting = true
                case .error(let e):
                    statusText = "连接失败：\(e)"
                default:
                    break
                }
            }
        }
        conn.connect()

        // 3. 弹系统广播选择器（选 ZEEHOCastBroadcast）
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            presentBroadcastPicker()
        }
    }

    private func presentBroadcastPicker() {
        if let window = UIApplication.shared.windows.first {
            let picker = RPSystemBroadcastPickerView(
                frame: CGRect(x: window.bounds.midX - 50, y: window.bounds.midY - 50, width: 100, height: 100)
            )
            picker.preferredExtension = "com.zeeho.cast.broadcast"
            picker.showsMicrophoneButton = false
            window.addSubview(picker)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                picker.removeFromSuperview()
            }
        }
    }

    private func stopCast() {
        isCasting = false
        statusText = "正在断开…"
        bridge.stop()
        ECConnectionManager.shared.close()
        statusText = "已断开"
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) {
            presentationMode.wrappedValue.dismiss()
        }
    }

    private func exportLog() {
        let path = ECLog.shared.exportToDocuments()
        statusText = "日志已导出"
        print("日志: \(path)")
    }
}

/// 全局连接管理器（保证帧推流引用一致）
final class ECConnectionManager {
    static let shared = ECConnectionManager()
    var conn: ECConnection?

    func sendVideoFrame(_ data: Data, isKeyFrame: Bool) {
        conn?.sendVideoFrame(data, isKeyFrame: isKeyFrame)
    }

    func close() {
        conn?.close()
        conn = nil
    }
}
