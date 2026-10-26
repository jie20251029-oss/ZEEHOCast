import Foundation
import Network

/// EC 连接：TCP 1807 + 帧收发 + 握手状态机
final class ECConnection {
    enum State: Equatable {
        case idle, connecting, handshake, ready, streaming, error(String), closed
    }

    private(set) var state: State = .idle
    var onState: ((State) -> Void)?
    var onCommand: ((UInt16, Data) -> Void)?

    private var conn: NWConnection?
    private var queue = DispatchQueue(label: "ec.conn")
    private var rxBuffer = Data()

    init(host: String, port: Int) {
        let h = NWEndpoint.Host(host)
        let p = NWEndpoint.Port(rawValue: UInt16(port))!
        conn = NWConnection(host: h, port: p, using: .tcp)
        conn?.parameters.expeditedDelivery = true
        conn?.parameters.allowLocalEndpointReuse = true
    }

    func connect() {
        guard let c = conn else { return }
        setState(.connecting)
        ECLog.shared.i("conn", "连接 \(c.endpoint)")
        c.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.setState(.handshake)
                self?.sendFrame(cmd: ECCmd.ping, payload: [ECConstants.protocolVersion, 0, 0, 0])
                self?.startReceive()
            case .failed(let e):
                self?.setState(.error(e.localizedDescription))
            case .cancelled:
                self?.setState(.closed)
            default:
                break
            }
        }
        c.start(queue: queue)
    }

    private func startReceive() {
        conn?.receive(minimumIncompleteLength: ECFrame.headerSize, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let d = data, !d.isEmpty {
                self.rxBuffer.append(d)
                self.drainFrames()
            }
            if isComplete || error != nil {
                if case .closed = self.state { return }
                self.setState(.error("连接中断"))
                return
            }
            self.startReceive()
        }
    }

    private func drainFrames() {
        while let f = ECFrame.parse(rxBuffer) {
            rxBuffer.removeFirst(ECFrame.headerSize + f.payload.count)
            ECLog.shared.hex("rx", Data(ECFrame.build(cmd: f.cmd, payload: [UInt8](f.payload), flags: f.flags)))
            handleFrame(f)
        }
    }

    private func handleFrame(_ f: (cmd: UInt16, flags: UInt8, payload: Data)) {
        switch f.cmd {
        case ECCmd.pong:
            ECLog.shared.i("conn", "收到 PONG，握手成功")
            setState(.ready)
            sendFrame(cmd: ECCmd.hello, payload: Array("ZEEHOCast/1.0".utf8))
        case ECCmd.mirrorAck:
            ECLog.shared.i("conn", "镜像通道已确认")
            setState(.streaming)
        default:
            onCommand?(f.cmd, f.payload)
        }
    }

    func sendFrame(cmd: UInt16, payload: [UInt8], flags: UInt8 = ECFrame.flagRequest) {
        let frame = ECFrame.build(cmd: cmd, payload: payload, flags: flags)
        ECLog.shared.hex("tx", frame)
        conn?.send(content: frame, completion: .contentProcessed { _ in })
    }

    func openMirror(width: Int, height: Int, bitrate: Int, fps: Int) {
        var p = [UInt8](repeating: 0, count: 12)
        p[0] = UInt8(width >> 8); p[1] = UInt8(width & 0xFF)
        p[2] = UInt8(height >> 8); p[3] = UInt8(height & 0xFF)
        p[4] = UInt8((bitrate >> 24) & 0xFF); p[5] = UInt8((bitrate >> 16) & 0xFF)
        p[6] = UInt8((bitrate >> 8) & 0xFF); p[7] = UInt8(bitrate & 0xFF)
        p[8] = UInt8(fps)
        p[9] = ECConstants.transportWIFI
        sendFrame(cmd: ECCmd.openMirror, payload: p)
    }

    func startMirror() {
        sendFrame(cmd: ECCmd.startMirror, payload: [0])
    }

    func sendVideoInfo(width: Int, height: Int, bitrate: Int) {
        var p = [UInt8](repeating: 0, count: 12)
        p[0] = UInt8(width >> 8); p[1] = UInt8(width & 0xFF)
        p[2] = UInt8(height >> 8); p[3] = UInt8(height & 0xFF)
        p[4] = UInt8((bitrate >> 24) & 0xFF); p[5] = UInt8((bitrate >> 16) & 0xFF)
        p[6] = UInt8((bitrate >> 8) & 0xFF); p[7] = UInt8(bitrate & 0xFF)
        sendFrame(cmd: ECCmd.videoInfo, payload: p)
    }

    func sendVideoFrame(_ data: Data, isKeyFrame: Bool) {
        let flags = isKeyFrame ? ECFrame.flagStream | 0x08 : ECFrame.flagStream
        conn?.send(content: ECFrame.build(cmd: ECCmd.videoFrame, payload: [UInt8](data), flags: flags),
                   completion: .contentProcessed { _ in })
        ECLog.shared.hex("tx-v", data)
    }

    func close() {
        conn?.cancel()
        conn = nil
        setState(.closed)
    }

    private func setState(_ s: State) {
        state = s
        onState?(s)
    }
}
