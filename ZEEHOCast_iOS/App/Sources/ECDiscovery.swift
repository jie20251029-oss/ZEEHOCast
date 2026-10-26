import Foundation
import Network

/// 仪表发现设备
struct ECDevice: Identifiable, Equatable {
    let id = UUID()
    let name: String
    let host: String
    let port: Int
}

/// mDNS 自动发现（NWBrowser 查询 _EasyConn._tcp，命中后用 NetService 解析出 IP）
final class ECDiscovery: NSObject {
    private var browser: NWBrowser?
    private var services: [NetService] = []
    private var resolving = Set<String>()
    private var resolved = Set<String>()
    var onFound: ((ECDevice) -> Void)?

    func start(onFound: @escaping (ECDevice) -> Void) {
        stop()
        self.onFound = onFound
        let params = NWParameters()
        params.includePeerToPeer = true
        let b = NWBrowser(for: .bonjour(type: ECConstants.serviceType, domain: nil), using: params)
        browser = b
        b.stateUpdateHandler = { state in
            ECLog.shared.i("mdns", "浏览器状态: \(state)")
        }
        b.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self = self else { return }
            for r in results {
                switch r.endpoint {
                case .hostPort(let host, let port):
                    self.emit(name: r.name, host: "\(host)", port: Int(port.rawValue))
                case .service(let name, let type, let domain, _):
                    // Bonjour 结果只有服务名，需要再解析才知道 IP
                    self.resolve(name: name, type: type, domain: domain)
                default:
                    break
                }
            }
        }
        b.start(queue: .main)
    }

    private func resolve(name: String, type: String, domain: String) {
        guard !resolving.contains(name) else { return }
        resolving.insert(name)
        let svc = NetService(domain: domain, type: type, name: name)
        svc.delegate = self
        services.append(svc)   // 必须持有，否则解析回调前就被释放
        svc.resolve(withTimeout: 5)
    }

    private func emit(name: String, host: String, port: Int) {
        let key = "\(name)|\(host)|\(port)"
        guard !resolved.contains(key) else { return }
        resolved.insert(key)
        onFound?(ECDevice(name: name, host: host, port: port))
    }

    private func finish(_ s: NetService) {
        s.stop()
        services.removeAll { $0 === s }
        resolving.remove(s.name)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        services.forEach { $0.stop() }
        services.removeAll()
        resolving.removeAll()
        resolved.removeAll()
    }
}

extension ECDiscovery: NetServiceDelegate {
    func netServiceDidResolveAddress(_ sender: NetService) {
        var host = sender.hostName
        if let addrs = sender.addresses {
            for data in addrs {
                var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                let rc = data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
                    guard let base = raw.baseAddress else { return -1 }
                    return getnameinfo(
                        base.assumingMemoryBound(to: sockaddr.self),
                        socklen_t(data.count),
                        &buf,
                        socklen_t(buf.count),
                        nil,
                        0,
                        NI_NUMERICHOST
                    )
                }
                if rc == 0 {
                    let ip = String(cString: buf)
                    if !ip.contains(":") {   // 优先取 IPv4
                        host = ip
                        break
                    }
                }
            }
        }
        if let h = host {
            ECLog.shared.i("mdns", "解析 \(sender.name) -> \(h):\(sender.port)")
            emit(name: sender.name, host: h, port: sender.port)
        }
        finish(sender)
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        ECLog.shared.i("mdns", "解析失败 \(sender.name): \(errorDict)")
        finish(sender)
    }
}
