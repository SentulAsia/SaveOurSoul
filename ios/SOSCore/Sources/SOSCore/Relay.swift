import Foundation

/// Bounded set of frame ids a relay has already handled, so mesh floods terminate.
public final class SeenCache: @unchecked Sendable {
    private let capacity: Int
    private var order: [UUID] = []
    private var set: Set<UUID> = []
    private let lock = NSLock()

    public init(capacity: Int = 4096) { self.capacity = capacity }

    /// True the first time `id` is offered, false for repeats.
    public func firstSeen(_ id: UUID) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard set.insert(id).inserted else { return false }
        order.append(id)
        if order.count > capacity { set.remove(order.removeFirst()) }
        return true
    }
}

/// What a phone does with a frame received from a nearby peer. Relays cannot verify the MAC;
/// the backend does.
public struct RelayPolicy: Sendable {
    public enum Decision: Equatable {
        /// Queue for upload and re-broadcast; the hop counter is already incremented.
        case relay(Data)
        case drop(String)
    }

    private let seen: SeenCache
    private let maxHops: UInt8

    public init(seen: SeenCache, maxHops: UInt8 = FrameCodec.maxHops) {
        self.seen = seen
        self.maxHops = maxHops
    }

    public func onFrameFromPeer(_ raw: Data) -> Decision {
        let frame: PanicFrame
        do { frame = try FrameCodec.decode(raw) } catch { return .drop("malformed") }
        guard seen.firstSeen(frame.frameId) else { return .drop("already seen") }
        guard frame.hops < maxHops else { return .drop("hop limit") }
        return .relay(FrameCodec.withHops(raw, frame.hops + 1))
    }
}
