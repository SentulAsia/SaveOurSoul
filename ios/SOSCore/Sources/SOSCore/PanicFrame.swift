import CryptoKit
import Foundation

/// Emergency category, encoded as one byte. Order is part of the wire protocol.
public enum SosKind: UInt8, CaseIterable, Codable, Sendable {
    case general, medical, fire, violence, accident
}

public struct FrameFlags: OptionSet, Sendable, Hashable {
    public let rawValue: UInt8
    public init(rawValue: UInt8) { self.rawValue = rawValue }
    public static let hasLocation = FrameFlags(rawValue: 0x01)
    public static let cancel = FrameFlags(rawValue: 0x02)
    public static let test = FrameFlags(rawValue: 0x04)
}

/// SOS wire protocol v1 (docs/PROTOCOL.md). 83 bytes, big-endian, byte-compatible with the
/// backend (TypeScript) and Android (Kotlin) implementations.
public struct PanicFrame: Equatable, Sendable {
    public static let version: UInt8 = 1
    public static let unknownAccuracy: UInt16 = 0xFFFF
    public static let unknownBattery: UInt8 = 0xFF

    public var version: UInt8 = PanicFrame.version
    public var flags: FrameFlags
    public var kind: UInt8
    /// Relay hop counter. Not covered by the MAC; relays increment it.
    public var hops: UInt8 = 0
    public var frameId: UUID
    public var alertId: UUID
    public var deviceId: UUID
    /// Unix seconds.
    public var timestamp: UInt32
    public var latE7: Int32 = 0
    public var lonE7: Int32 = 0
    public var accuracyM: UInt16 = PanicFrame.unknownAccuracy
    public var batteryPct: UInt8 = PanicFrame.unknownBattery

    public init(version: UInt8 = PanicFrame.version, flags: FrameFlags, kind: UInt8, hops: UInt8 = 0,
                frameId: UUID, alertId: UUID, deviceId: UUID, timestamp: UInt32,
                latE7: Int32 = 0, lonE7: Int32 = 0,
                accuracyM: UInt16 = PanicFrame.unknownAccuracy, batteryPct: UInt8 = PanicFrame.unknownBattery) {
        self.version = version; self.flags = flags; self.kind = kind; self.hops = hops
        self.frameId = frameId; self.alertId = alertId; self.deviceId = deviceId; self.timestamp = timestamp
        self.latE7 = latE7; self.lonE7 = lonE7; self.accuracyM = accuracyM; self.batteryPct = batteryPct
    }

    public var isCancel: Bool { flags.contains(.cancel) }
    public var hasLocation: Bool { flags.contains(.hasLocation) }
}

/// Produces the frame MAC (HMAC-SHA256). The app backs this with a Keychain-stored key.
public protocol FrameSigner: Sendable {
    func hmacSHA256(_ data: Data) -> Data
}

public struct SymmetricKeySigner: FrameSigner {
    private let key: SymmetricKey
    public init(secret: Data) { key = SymmetricKey(data: secret) }
    public func hmacSHA256(_ data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: key))
    }
}

public enum FrameError: Error, Equatable {
    case badLength(Int)
    case unsupportedVersion(UInt8)
    case notBase64Url
}

public enum FrameCodec {
    public static let frameLength = 83
    public static let signedLength = 67
    public static let macLength = 16
    public static let hopsOffset = 3
    public static let maxHops: UInt8 = 8
    public static let smsPrefix = "SOS1:"

    public static func encode(_ f: PanicFrame, signer: FrameSigner) -> Data {
        var b = [UInt8]()
        b.reserveCapacity(frameLength)
        b += [f.version, f.flags.rawValue, f.kind, f.hops]
        b += bytes(of: f.frameId) + bytes(of: f.alertId) + bytes(of: f.deviceId)
        b += bigEndian(f.timestamp) + bigEndian(UInt32(bitPattern: f.latE7)) + bigEndian(UInt32(bitPattern: f.lonE7))
        b += [UInt8(f.accuracyM >> 8), UInt8(f.accuracyM & 0xFF), f.batteryPct]
        b += mac(b, signer: signer)
        return Data(b)
    }

    /// Parses a frame without checking the MAC (only the sender and backend hold the key).
    public static func decode(_ data: Data) throws -> PanicFrame {
        let b = [UInt8](data)
        guard b.count == frameLength else { throw FrameError.badLength(b.count) }
        guard b[0] == PanicFrame.version else { throw FrameError.unsupportedVersion(b[0]) }
        return PanicFrame(
            version: b[0], flags: FrameFlags(rawValue: b[1]), kind: b[2], hops: b[3],
            frameId: uuid(b, 4), alertId: uuid(b, 20), deviceId: uuid(b, 36),
            timestamp: u32(b, 52), latE7: Int32(bitPattern: u32(b, 56)), lonE7: Int32(bitPattern: u32(b, 60)),
            accuracyM: UInt16(b[64]) << 8 | UInt16(b[65]), batteryPct: b[66]
        )
    }

    public static func verify(_ data: Data, signer: FrameSigner) -> Bool {
        let b = [UInt8](data)
        guard b.count == frameLength else { return false }
        let expected = mac(Array(b[0..<signedLength]), signer: signer)
        // Constant-time comparison.
        return zip(expected, b[signedLength...]).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    public static func hops(_ data: Data) -> UInt8 { [UInt8](data)[hopsOffset] }

    /// Copy with a new hop counter; the MAC stays valid because hops are not signed.
    public static func withHops(_ data: Data, _ hops: UInt8) -> Data {
        var b = [UInt8](data)
        b[hopsOffset] = hops
        return Data(b)
    }

    public static func frameId(_ data: Data) -> UUID { uuid([UInt8](data), 4) }

    public static func toBase64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func fromBase64Url(_ s: String) throws -> Data {
        var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        t += String(repeating: "=", count: (4 - t.count % 4) % 4)
        guard let d = Data(base64Encoded: t) else { throw FrameError.notBase64Url }
        return d
    }

    /// 116 GSM-7-safe characters: always a single SMS.
    public static func toSms(_ data: Data) -> String { smsPrefix + toBase64Url(data) }

    /// Mesh payloads carry several frames back to back.
    public static func split(_ payload: Data) -> [Data] {
        let b = [UInt8](payload)
        return stride(from: 0, to: b.count - frameLength + 1, by: frameLength).map { Data(b[$0..<$0 + frameLength]) }
    }

    public static func join(_ frames: [Data]) -> Data { frames.reduce(into: Data()) { $0.append($1) } }

    // MARK: - helpers

    private static func mac(_ signed: [UInt8], signer: FrameSigner) -> [UInt8] {
        var copy = Array(signed.prefix(signedLength))
        copy[hopsOffset] = 0
        return Array(signer.hmacSHA256(Data(copy)).prefix(macLength))
    }

    private static func bytes(of u: UUID) -> [UInt8] { withUnsafeBytes(of: u.uuid) { Array($0) } }

    private static func uuid(_ b: [UInt8], _ o: Int) -> UUID {
        Array(b[o..<o + 16]).withUnsafeBufferPointer { NSUUID(uuidBytes: $0.baseAddress!) as UUID }
    }

    private static func bigEndian(_ v: UInt32) -> [UInt8] {
        [UInt8(v >> 24 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)]
    }

    private static func u32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) << 24 | UInt32(b[o + 1]) << 16 | UInt32(b[o + 2]) << 8 | UInt32(b[o + 3])
    }
}
