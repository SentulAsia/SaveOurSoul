import CryptoKit
import Foundation

/// Backend delivery receipt: HMAC(device secret, "SOSACK1" || frameId)[:8]. Only the backend and
/// the sending phone hold the secret, so a relayed "delivered" can be checked by the victim.
public struct Ack: Equatable, Sendable {
    public let frameId: UUID
    public let receipt: Data
    public init(frameId: UUID, receipt: Data) {
        self.frameId = frameId
        self.receipt = receipt
    }
}

public enum Receipts {
    public static let length = 8
    public static let ackLength = 16 + length
    private static let domain = Data("SOSACK1".utf8)

    public static func compute(_ frameId: UUID, signer: FrameSigner) -> Data {
        Data(signer.hmacSHA256(domain + uuidBytes(frameId)).prefix(length))
    }

    public static func verify(_ ack: Ack, signer: FrameSigner) -> Bool {
        let expected = [UInt8](compute(ack.frameId, signer: signer))
        let got = [UInt8](ack.receipt)
        guard got.count == length else { return false }
        return zip(expected, got).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    public static func encode(_ acks: [Ack]) -> Data {
        acks.reduce(into: Data()) { out, a in
            out.append(uuidBytes(a.frameId))
            out.append(a.receipt.prefix(length) + Data(count: max(0, length - a.receipt.count)))
        }
    }

    public static func decode(_ data: Data) -> [Ack] {
        let b = [UInt8](data)
        return stride(from: 0, to: b.count - ackLength + 1, by: ackLength).map { o in
            let id = Array(b[o..<o + 16]).withUnsafeBufferPointer { NSUUID(uuidBytes: $0.baseAddress!) as UUID }
            return Ack(frameId: id, receipt: Data(b[o + 16..<o + ackLength]))
        }
    }

    static func uuidBytes(_ u: UUID) -> Data { withUnsafeBytes(of: u.uuid) { Data($0) } }
}

/// Recently confirmed frames this phone knows about, served to peers so they stop spreading them.
public final class AckLedger: @unchecked Sendable {
    private let capacity: Int
    private var acks: [Ack] = []
    private let lock = NSLock()

    public init(capacity: Int = 64) { self.capacity = capacity }

    public func add(_ ack: Ack) {
        lock.lock(); defer { lock.unlock() }
        acks.removeAll { $0.frameId == ack.frameId }
        acks.append(ack)
        if acks.count > capacity { acks.removeFirst(acks.count - capacity) }
    }

    /// Newest first.
    public func recent(_ max: Int) -> [Ack] {
        lock.lock(); defer { lock.unlock() }
        return Array(acks.reversed().prefix(max))
    }
}

/// Per-environment radio namespace (same values as Android's MeshNamespace), so dev and testing
/// phones never exchange frames with production (prod) phones.
public enum MeshNamespace: String, CaseIterable, Sendable {
    case prod = "PROD"
    case test = "TEST"
    case dev = "DEV"

    public var bleServiceUUID: UUID {
        switch self {
        case .prod: return UUID(uuidString: "5a0e5011-0000-4c8a-9f3e-5a0e50115a0e")!
        case .test: return UUID(uuidString: "5a0e5011-0000-4c8a-9f3e-5a0e50115a7e")!
        case .dev: return UUID(uuidString: "5a0e5011-0000-4c8a-9f3e-5a0e50115ade")!
        }
    }

    public var nearbyServiceId: String {
        switch self {
        case .prod: return "org.saveoursoul.sos.v1"
        case .test: return "org.saveoursoul.sos.v1.test"
        case .dev: return "org.saveoursoul.sos.v1.dev"
        }
    }
}

/// SOS-BLE v1. Mirrors android/core/.../BleProtocol.kt; see docs/BLE.md.
public enum BleProtocol {
    /// Production service UUID; see MeshNamespace for the other environments.
    public static let serviceUUID = MeshNamespace.prod.bleServiceUUID
    public static let inboxUUID = UUID(uuidString: "5a0e5011-0001-4c8a-9f3e-5a0e50115a0e")!
    public static let outboxUUID = UUID(uuidString: "5a0e5011-0002-4c8a-9f3e-5a0e50115a0e")!
    public static let acksUUID = UUID(uuidString: "5a0e5011-0003-4c8a-9f3e-5a0e50115a0e")!
    public static let confirmedUUID = UUID(uuidString: "5a0e5011-0004-4c8a-9f3e-5a0e50115a0e")!

    public static let maxValue = 512
    public static let maxFramesPerValue = maxValue / FrameCodec.frameLength // 6
    public static let maxAcksPerValue = maxValue / Receipts.ackLength // 21

    public static let flagOwnSos: UInt8 = 0x01
    public static let flagHasInternet: UInt8 = 0x02
    public static let flagHoldsFrames: UInt8 = 0x04

    public struct Beacon: Equatable, Sendable {
        public var flags: UInt8
        /// 24 bits of SHA-256 over the sorted ids of the frames held.
        public var digest: UInt32

        public init(flags: UInt8, digest: UInt32) {
            self.flags = flags
            self.digest = digest & 0xFFFFFF
        }

        public var ownSos: Bool { flags & BleProtocol.flagOwnSos != 0 }
        public var hasInternet: Bool { flags & BleProtocol.flagHasInternet != 0 }
        public var holdsFrames: Bool { flags & BleProtocol.flagHoldsFrames != 0 }

        /// Android advertises service data.
        public func serviceData() -> Data {
            Data([1, flags, UInt8(digest >> 16 & 0xFF), UInt8(digest >> 8 & 0xFF), UInt8(digest & 0xFF)])
        }

        /// iOS can only advertise a local name: "S1" + flags + 6 hex digits.
        public var localName: String { String(format: "S1%01x%06x", Int(flags & 0xF), Int(digest)) }

        public static func from(serviceData d: Data?) -> Beacon? {
            guard let d else { return nil }
            let b = [UInt8](d)
            guard b.count >= 5, b[0] == 1 else { return nil }
            return Beacon(flags: b[1], digest: UInt32(b[2]) << 16 | UInt32(b[3]) << 8 | UInt32(b[4]))
        }

        public static func from(localName n: String?) -> Beacon? {
            guard let n, n.count == 9, n.hasPrefix("S1"),
                  let f = UInt8(n.dropFirst(2).prefix(1), radix: 16),
                  let d = UInt32(n.dropFirst(3), radix: 16) else { return nil }
            return Beacon(flags: f, digest: d)
        }
    }

    public static func digest(_ ids: [UUID]) -> UInt32 {
        guard !ids.isEmpty else { return 0 }
        let sorted = ids.map { [UInt8](Receipts.uuidBytes($0)) }.sorted { $0.lexicographicallyPrecedes($1) }
        var h = SHA256()
        sorted.forEach { h.update(data: Data($0)) }
        let d = [UInt8](h.finalize())
        return UInt32(d[0]) << 16 | UInt32(d[1]) << 8 | UInt32(d[2])
    }
}

/// Crowd contact rules (same as Android): push to online phones, pull when online or from
/// victims, pull from plain relays only occasionally, never re-sync too soon, ignore weak signals.
public final class ContactPolicy: @unchecked Sendable {
    public enum Action: Equatable { case none, push, pull, exchange }

    public struct Local {
        public var holdsUndelivered: Bool
        public var hasInternet: Bool
        public init(holdsUndelivered: Bool, hasInternet: Bool) {
            self.holdsUndelivered = holdsUndelivered
            self.hasInternet = hasInternet
        }
    }

    private let cooldown: TimeInterval
    private let relayPullProbability: Double
    private let minRssi: Int
    private let random: () -> Double
    private var lastContact: [String: TimeInterval] = [:]
    private let lock = NSLock()

    public init(cooldown: TimeInterval = 60, relayPullProbability: Double = 0.15, minRssi: Int = -92,
                random: @escaping () -> Double = { Double.random(in: 0..<1) }) {
        self.cooldown = cooldown
        self.relayPullProbability = relayPullProbability
        self.minRssi = minRssi
        self.random = random
    }

    public func decide(peerKey: String, beacon: BleProtocol.Beacon?, rssi: Int, local: Local, now: TimeInterval) -> Action {
        guard rssi >= minRssi else { return .none }
        let peerHolds = beacon?.holdsFrames ?? true
        let peerVictim = beacon?.ownSos ?? false
        let peerOnline = beacon?.hasInternet ?? false

        let push = local.holdsUndelivered && peerOnline
        let pull = peerHolds && (local.hasInternet || peerVictim || random() < relayPullProbability)
        let action: Action = push && pull ? .exchange : push ? .push : pull ? .pull : .none
        guard action != .none else { return .none }

        lock.lock(); defer { lock.unlock() }
        let key = "\(peerKey)/\(beacon.map { Int($0.digest) } ?? -1)/\(local.holdsUndelivered)"
        if let last = lastContact[key], now - last < cooldown { return .none }
        if lastContact.count > 2048 { lastContact.removeAll() }
        lastContact[key] = now
        return action
    }
}

/// Picks frames for OUTBOX reads: own first, then least served, fewest hops, newest.
public final class OutboxRotation: @unchecked Sendable {
    private var served: [UUID: Int] = [:]
    private let lock = NSLock()

    public init() {}

    public func pick(_ entries: [Outbox.Entry], max: Int = BleProtocol.maxFramesPerValue) -> [Data] {
        lock.lock(); defer { lock.unlock() }
        let chosen = entries.sorted { a, b in
            if a.own != b.own { return a.own }
            let sa = served[a.frameId] ?? 0, sb = served[b.frameId] ?? 0
            if sa != sb { return sa < sb }
            let ha = FrameCodec.hops(a.frame), hb = FrameCodec.hops(b.frame)
            if ha != hb { return ha < hb }
            return a.addedAt > b.addedAt
        }.prefix(max)
        chosen.forEach { served[$0.frameId, default: 0] += 1 }
        if served.count > 4096 { served = served.filter { k, _ in entries.contains { $0.frameId == k } } }
        return chosen.map(\.frame)
    }
}
