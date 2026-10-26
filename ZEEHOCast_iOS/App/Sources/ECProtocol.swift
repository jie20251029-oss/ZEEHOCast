import Foundation

/// 极核 AE5i 投屏协议层（亿连 EC-SDK 逆向实现）
/// 仪表 WiFi AP，TCP 端口 1807，mDNS _EasyConn._tcp，EC_Ping 握手，H.264 镜像帧。
enum ECConstants {
    static let defaultHost = "192.168.49.1"
    static let defaultPort = 1807
    static let serviceType = "_EasyConn._tcp"
    static let transportWIFI: UInt8 = 0
    static let protocolVersion: UInt8 = 1
}

/// EC 命令 ID（逆向推断映射，实测可调）
enum ECCmd {
    static let ping: UInt16 = 0x0001
    static let pong: UInt16 = 0x0002
    static let hello: UInt16 = 0x0003
    static let openTransport: UInt16 = 0x0010
    static let openMirror: UInt16 = 0x0020
    static let startMirror: UInt16 = 0x0021
    static let closeMirror: UInt16 = 0x0022
    static let mirrorAck: UInt16 = 0x0023
    static let videoFrame: UInt16 = 0x0030
    static let videoInfo: UInt16 = 0x0031
    static let iframeRequest: UInt16 = 0x0032
    static let audioFrame: UInt16 = 0x0040
    static let bulkData: UInt16 = 0x0100
    static let customData: UInt16 = 0x0101
    static let touchEvent: UInt16 = 0x0110
    static let btnEvent: UInt16 = 0x0111
    static let vehicleSpeed: UInt16 = 0x0200
    static let engineRpm: UInt16 = 0x0201
    static let odometer: UInt16 = 0x0202
    static let gearStatus: UInt16 = 0x0203
    static let nightMode: UInt16 = 0x0204
    static let lightStatus: UInt16 = 0x0205
    static let drivingStatus: UInt16 = 0x0206
    static let location: UInt16 = 0x0207
    static let compass: UInt16 = 0x0208
    static let tirePressure: UInt16 = 0x0209
    static let accelerometer: UInt16 = 0x020A
    static let gyroscope: UInt16 = 0x020B
    static let gpsSatellite: UInt16 = 0x020C
    static let carStatus: UInt16 = 0x020D
    static let vehicleInfo: UInt16 = 0x020E
    static let enableHud: UInt16 = 0x0300
    static let disableHud: UInt16 = 0x0301
    static let hudFrame: UInt16 = 0x0302
    static let checkSN: UInt16 = 0x0400
    static let checkCarUuid: UInt16 = 0x0401
    static let downloadLicense: UInt16 = 0x0402
    static let registerUuid: UInt16 = 0x0403
    static let otaStatus: UInt16 = 0x0404
    static let otaData: UInt16 = 0x0405
}

/// EC 帧封装（magic + ver + cmd + flags + len + payload，大端）
enum ECFrame {
    static let magic: [UInt8] = [0x45, 0x43, 0x41, 0x50] // "ECAP"
    static let headerSize = 12
    static let flagRequest: UInt8 = 0x01
    static let flagResponse: UInt8 = 0x02
    static let flagStream: UInt8 = 0x04

    static func build(cmd: UInt16, payload: [UInt8], flags: UInt8 = flagRequest) -> Data {
        var d = Data()
        d.append(contentsOf: magic)
        d.append(ECConstants.protocolVersion)
        d.append(UInt8(cmd >> 8))
        d.append(UInt8(cmd & 0xFF))
        d.append(flags)
        let len = UInt32(payload.count)
        d.append(UInt8((len >> 24) & 0xFF))
        d.append(UInt8((len >> 16) & 0xFF))
        d.append(UInt8((len >> 8) & 0xFF))
        d.append(UInt8(len & 0xFF))
        d.append(contentsOf: payload)
        return d
    }

    /// 解析一帧，返回 (cmd, flags, payload)；不足或magic错返回 nil
    static func parse(_ data: Data) -> (cmd: UInt16, flags: UInt8, payload: Data)? {
        guard data.count >= headerSize else { return nil }
        let bytes = [UInt8](data)
        guard Array(bytes[0..<4]) == magic else { return nil }
        let cmd = UInt16(bytes[5]) << 8 | UInt16(bytes[6])
        let flags = bytes[7]
        let len = UInt32(bytes[8]) << 24 | UInt32(bytes[9]) << 16 | UInt32(bytes[10]) << 8 | UInt32(bytes[11])
        guard data.count >= headerSize + Int(len) else { return nil }
        let payload = data.subdata(in: (headerSize)..<(headerSize + Int(len)))
        return (cmd, flags, payload)
    }

    /// 解析扫码内容 → (host, port)
    static func parseTarget(_ raw: String) -> (host: String, port: Int)? {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let m = t.range(of: #"zeeho://([\d.]+)(?::(\d+))?"#, options: .regularExpression) {
            let s = String(t[m])
            let host = s.replacingOccurrences(of: "zeeho://", with: "")
                .split(separator: ":").first.map(String.init) ?? s
            let port = s.split(separator: ":").dropFirst().first.flatMap { Int($0) } ?? ECConstants.defaultPort
            return (host, port)
        }
        if let m = t.range(of: #"https?://([\d.]+)(?::(\d+))?"#, options: .regularExpression) {
            let s = String(t[m])
                .replacingOccurrences(of: "https://", with: "")
                .replacingOccurrences(of: "http://", with: "")
            let host = s.split(separator: ":").first.map(String.init) ?? s
            let port = s.split(separator: ":").dropFirst().first.flatMap { Int($0) } ?? ECConstants.defaultPort
            return (host, port)
        }
        if t.range(of: #"^\d{1,3}(\.\d{1,3}){3}(:\d+)?$"#, options: .regularExpression) != nil {
            let host = t.split(separator: ":").first.map(String.init) ?? t
            let port = t.split(separator: ":").dropFirst().first.flatMap { Int($0) } ?? ECConstants.defaultPort
            return (host, port)
        }
        return nil
    }
}

/// 日志总线（协议 HEX 明细 + 导出）
final class ECLog {
    static let shared = ECLog()
    private var buffer = ""
    private var listeners: [(String) -> Void] = []
    private let lock = NSLock()

    func addListener(_ l: @escaping (String) -> Void) {
        lock.lock(); listeners.append(l); lock.unlock()
    }

    func i(_ tag: String, _ msg: String) {
        lock.lock()
        buffer += "[\(tag)] \(msg)\n"
        if buffer.count > 65536 {
            buffer = String(buffer.suffix(16384))
        }
        let line = "[\(tag)] \(msg)"
        let ls = listeners
        lock.unlock()
        ls.forEach { $0(line) }
        print(line)
    }

    func hex(_ tag: String, _ data: Data) {
        let show = data.prefix(512)
        let s = show.map { String(format: "%02X", $0) }.joined(separator: " ")
        i(tag, "HEX[\(data.count)B] \(s)")
    }

    func clear() { lock.lock(); buffer = ""; lock.unlock() }

    func dump() -> String { lock.lock(); defer { lock.unlock() }; return buffer }

    func exportToDocuments() -> String {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let f = dir.appendingPathComponent("zeeho_cast_\(Int(Date().timeIntervalSince1970)).log")
        try? dump().write(to: f, atomically: true, encoding: .utf8)
        return f.path
    }
}
