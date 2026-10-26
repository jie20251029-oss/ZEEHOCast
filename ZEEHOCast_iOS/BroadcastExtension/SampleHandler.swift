import ReplayKit
import Network

/// 广播扩展：系统录屏帧 → localhost:53001 → 主App
/// RPSampleBufferType.video 的 CMSampleBuffer 为已编码 H.264，直接透传。
/// 注意：扩展是独立进程/target，不引用主 App 任何类，仅通过本地 TCP socket 通信。
final class SampleHandler: RPBroadcastSampleHandler {

    private static let listenPort: UInt16 = 53001

    private var conn: NWConnection?
    private var queue = DispatchQueue(label: "broadcast.sample")

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        print("[ext] 录屏扩展启动")
        connectToApp()
    }

    override func broadcastPaused() {
        print("[ext] 录屏暂停")
    }

    override func broadcastResumed() {
        print("[ext] 录屏恢复")
    }

    override func broadcastFinished() {
        print("[ext] 录屏结束")
        conn?.cancel()
        conn = nil
    }

    private func connectToApp() {
        let h = NWEndpoint.Host("127.0.0.1")
        let p = NWEndpoint.Port(rawValue: SampleHandler.listenPort)!
        let c = NWConnection(host: h, port: p, using: .tcp)
        conn = c
        c.stateUpdateHandler = { state in
            print("[ext] sock状态: \(state)")
        }
        c.start(queue: queue)
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with type: RPSampleBufferType) {
        guard type == .video else { return }
        guard let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        var len = 0
        var ptr: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil,
                                          totalLengthOut: &len, dataPointerOut: &ptr) == kCMBlockBufferNoErr,
              let dataPtr = ptr, len > 0 else { return }

        let raw = Data(bytes: dataPtr, count: len)

        // 帧格式：[4B长度][1B关键帧][H.264数据]
        var out = Data()
        out.append(UInt8((len >> 24) & 0xFF))
        out.append(UInt8((len >> 16) & 0xFF))
        out.append(UInt8((len >> 8) & 0xFF))
        out.append(UInt8(len & 0xFF))
        out.append(isKeyFrame(sampleBuffer) ? 1 : 0)
        out.append(raw)

        conn?.send(content: out, completion: .contentProcessed { _ in })
    }

    private func isKeyFrame(_ sb: CMSampleBuffer) -> Bool {
        guard let info = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]],
              let first = info.first else { return false }
        return (first[kCMSampleAttachmentKey_NotSync] as? Bool) != true
    }
}
