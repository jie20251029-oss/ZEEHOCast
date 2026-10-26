import Foundation
import Network

/// 主App侧：本地 TCP 服务，接收 Broadcast 扩展回传的录屏帧
/// 扩展 → localhost:53001 → 主App → EC 推流
/// 用本地socket而非App Group，免签（TrollStore/侧载）环境同样可用。
final class BroadcastBridge {
    static let shared = BroadcastBridge()
    static let listenPort: UInt16 = 53001

    private var listener: NWListener?
    private var queue = DispatchQueue(label: "bridge")
    private var frameCallback: ((Data, Bool) -> Void)?
    private var active = false

    func start(onFrame: @escaping (Data, Bool) -> Void) {
        frameCallback = onFrame
        guard !active else { return }
        active = true
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: Self.listenPort)!)
        guard let l = try? NWListener(using: params) else {
            ECLog.shared.i("bridge", "监听失败")
            return
        }
        listener = l
        l.newConnectionHandler = { [weak self] conn in
            self?.handleConnection(conn)
        }
        l.stateUpdateHandler = { state in
            ECLog.shared.i("bridge", "监听状态: \(state)")
        }
        l.start(queue: queue)
    }

    private func handleConnection(_ conn: NWConnection) {
        conn.start(queue: queue)
        receiveLoop(conn)
    }

    private func receiveLoop(_ conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 4, maximumLength: 4 * 1024 * 1024) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let d = data, !d.isEmpty {
                self.dispatchFrame(d, conn: conn)
            }
            if isComplete || error != nil {
                conn.cancel()
                return
            }
            self.receiveLoop(conn)
        }
    }

    /// 扩展帧格式：[4B长度][1B关键帧标记][payload]
    private func dispatchFrame(_ data: Data, conn: NWConnection) {
        guard data.count >= 5 else { return }
        let len = (Int(data[0]) << 24) | (Int(data[1]) << 16) | (Int(data[2]) << 8) | Int(data[3])
        let isKey = data[4] == 1
        let payload = data.subdata(in: 5..<min(data.count, 5 + len))
        ECLog.shared.hex("bridge", payload)
        frameCallback?(payload, isKey)
    }

    func stop() {
        active = false
        listener?.cancel()
        listener = nil
    }
}
