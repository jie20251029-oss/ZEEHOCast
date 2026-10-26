import Foundation
import Network

/// 仪表发现设备
struct ECDevice: Identifiable, Equatable {
    let id = UUID()
    let name: String
    let host: String
    let port: Int
}

/// mDNS 自动发现（NWBrowser 查询 _EasyConn._tcp）
final class ECDiscovery {
    private var browser: NWBrowser?
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
            for r in results {
                guard case .hostname(let hostname) = r.endpoint else { continue }
                let host = hostname
                let port = r.endpoint.port?.rawValue ?? ECConstants.defaultPort
                let name = r.name
                self?.onFound?(ECDevice(name: name, host: host, port: Int(port)))
            }
        }
        b.start(queue: .main)
    }

    func stop() {
        browser?.cancel()
        browser = nil
    }
}
