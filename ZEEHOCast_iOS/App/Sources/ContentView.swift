import SwiftUI

/// 极核投屏 · 主界面（自动发现 / 扫码 / 手动IP）
struct ContentView: View {
    @State private var tab: Int = 0
    @State private var devices: [ECDevice] = []
    @State private var searching = false
    @State private var manualIP = "192.168.49.1"
    @State private var manualPort = "1807"
    @State private var showCast = false
    @State private var toast: String?
    @State private var showQR = false
    @State private var logText = ""

    private var discovery = ECDiscovery()

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                header
                tabRow
                    .padding(.top, 20)

                Group {
                    if tab == 0 { discoveryPanel }
                    if tab == 1 { qrPanel }
                    if tab == 2 { manualPanel }
                }
                .padding(.top, 18)

                Spacer()

                footer
            }
            .padding(.horizontal, 20)

            if let t = toast {
                VStack {
                    Spacer()
                    Text(t)
                        .font(.system(size: 13))
                        .foregroundColor(Theme.textPrimary)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .background(Theme.card2)
                        .cornerRadius(12)
                        .padding(.bottom, 90)
                }
                .transition(.opacity)
            }
        }
        .sheet(isPresented: $showQR) {
            QRScannerView { result in
                showQR = false
                if let t = ECProtocol.parseTarget(result) {
                    manualIP = t.host
                    manualPort = String(t.port)
                    tab = 2
                    startCast(host: t.host, port: t.port)
                } else {
                    toast = "未识别到有效的仪表地址"
                }
            }
        }
        .fullScreenCover(isPresented: $showCast) {
            CastView()
        }
        .onAppear { startDiscovery() }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Circle()
                    .fill(Theme.accent)
                    .frame(width: 10, height: 10)
                Text("ZEEHO CAST")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .tracking(2)
                    .foregroundColor(Theme.textPrimary)
            }
            Text("极核仪表投屏 · AE5i")
                .font(.system(size: 13))
                .foregroundColor(Theme.textSecondary)
        }
    }

    private var tabRow: some View {
        HStack(spacing: 0) {
            tabButton("自动发现", index: 0)
            tabButton("扫码连接", index: 1)
            tabButton("手动连接", index: 2)
        }
        .padding(4)
        .background(Theme.card2)
        .cornerRadius(14)
    }

    private func tabButton(_ title: String, index: Int) -> some View {
        Button(action: {
            tab = index
            if index == 0 { startDiscovery() } else { discovery.stop() }
        }) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(tab == index ? .white : Theme.textSecondary)
                .frame(maxWidth: .infinity)
                .frame(height: 40)
                .background(tab == index ? Theme.accent : Color.clear)
                .cornerRadius(10)
        }
    }

    // ---------- 自动发现 ----------
    private var discoveryPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("可用仪表")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(Theme.textPrimary)
                Spacer()
                Button("重新扫描") { startDiscovery() }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(Theme.accent)
            }

            if devices.isEmpty {
                VStack(spacing: 10) {
                    Text(searching ? "正在扫描仪表 WiFi 网络…" : "未发现仪表设备")
                        .font(.system(size: 14))
                        .foregroundColor(Theme.textTertiary)
                    Text("请确认手机已连接仪表 WiFi")
                        .font(.system(size: 12))
                        .foregroundColor(Theme.textTertiary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(devices) { d in
                            deviceRow(d)
                        }
                    }
                }
            }
        }
        .cardStyle()
        .padding(16)
    }

    private func deviceRow(_ d: ECDevice) -> some View {
        Button(action: { startCast(host: d.host, port: d.port) }) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(Theme.accent.opacity(0.1)).frame(width: 44, height: 44)
                    Circle().fill(Theme.accent).frame(width: 14, height: 14)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(d.name)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(Theme.textPrimary)
                    Text("\(d.host):\(d.port)")
                        .font(.system(size: 12))
                        .foregroundColor(Theme.textSecondary)
                }
                Spacer()
                Text("AE5i")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.accent)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Theme.accent.opacity(0.1))
                    .cornerRadius(8)
            }
            .padding(14)
            .background(Theme.bg)
            .cornerRadius(16)
        }
    }

    private func startDiscovery() {
        searching = true
        devices.removeAll()
        discovery.start { dev in
            if !devices.contains(where: { $0.host == dev.host && $0.port == dev.port }) {
                devices.append(dev)
            }
            searching = false
        }
    }

    // ---------- 扫码 ----------
    private var qrPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("扫描仪表上的二维码快速连接")
                .font(.system(size: 13))
                .foregroundColor(Theme.textSecondary)
            Button(action: { showQR = true }) {
                VStack(spacing: 10) {
                    Image(systemName: "qrcode.viewfinder")
                        .font(.system(size: 36))
                        .foregroundColor(Theme.accent)
                    Text("扫描二维码")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(Theme.accent)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 120)
                .background(Theme.accent.opacity(0.08))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Theme.accent.opacity(0.4), lineWidth: 1))
                .cornerRadius(20)
            }
        }
        .cardStyle()
        .padding(16)
    }

    // ---------- 手动 ----------
    private var manualPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("仪表 IP 地址")
                .font(.system(size: 13))
                .foregroundColor(Theme.textSecondary)
            TextField("192.168.49.1", text: $manualIP)
                .keyboardType(.numbersAndPunctuation)
                .font(.system(size: 15))
                .foregroundColor(Theme.textPrimary)
                .padding(.horizontal, 16)
                .frame(height: 52)
                .background(Theme.bg)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.divider, lineWidth: 1))
                .cornerRadius(12)

            Text("端口")
                .font(.system(size: 13))
                .foregroundColor(Theme.textSecondary)
            TextField("1807", text: $manualPort)
                .keyboardType(.numberPad)
                .font(.system(size: 15))
                .foregroundColor(Theme.textPrimary)
                .padding(.horizontal, 16)
                .frame(height: 52)
                .background(Theme.bg)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.divider, lineWidth: 1))
                .cornerRadius(12)

            Button(action: {
                guard let p = Int(manualPort) else { toast = "端口无效"; return }
                startCast(host: manualIP, port: p)
            }) {
                Text("连接投屏")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 54)
                    .background(Theme.accent)
                    .cornerRadius(14)
            }
            .padding(.top, 6)
        }
        .cardStyle()
        .padding(16)
    }

    // ---------- 底部 ----------
    private var footer: some View {
        VStack(spacing: 6) {
            Text("本工具为个人维护用途，仅适用于极核 AE5i 仪表调试，请勿用于非法用途。")
                .font(.system(size: 11))
                .foregroundColor(Theme.textTertiary)
            HStack(spacing: 16) {
                Button("导出日志") { exportLog() }
                    .font(.system(size: 13))
                    .foregroundColor(Theme.textSecondary)
                Button("清空日志") {
                    ECLog.shared.clear()
                    toast = "日志已清空"
                }
                .font(.system(size: 13))
                .foregroundColor(Theme.textSecondary)
            }
            .padding(.top, 4)
        }
    }

    private func exportLog() {
        let path = ECLog.shared.exportToDocuments()
        toast = "日志已导出: \(path)"
    }

    private func startCast(host: String, port: Int) {
        ECLog.shared.i("ui", "连接目标 \(host):\(port)")
        showCast = true
    }
}
