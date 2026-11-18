import Foundation
import SOSCore
import XCTest

/// Same vectors as the backend and Android suites: all three implementations must agree byte for byte.
private struct Vectors: Decodable {
    struct Frame: Decodable {
        let version: UInt8, flags: UInt8, kind: UInt8, hops: UInt8
        let frameId: UUID, alertId: UUID, deviceId: UUID
        let timestamp: UInt32, latE7: Int32, lonE7: Int32, accuracyM: UInt16, batteryPct: UInt8
    }
    struct Vector: Decodable { let name: String; let frame: Frame; let base64url: String; let sms: String }
    let secretHex: String
    let vectors: [Vector]
}

private func hex(_ s: String) -> Data {
    var d = Data()
    var i = s.startIndex
    while i < s.endIndex {
        let j = s.index(i, offsetBy: 2)
        d.append(UInt8(s[i..<j], radix: 16)!)
        i = j
    }
    return d
}

final class PanicFrameTests: XCTestCase {
    private let vectors: Vectors = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("protocol/test-vectors.json")
        return try! JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
    }()

    private var signer: SymmetricKeySigner { SymmetricKeySigner(secret: hex(vectors.secretHex)) }

    func testEncodesLikeBackendAndAndroid() throws {
        for v in vectors.vectors {
            let f = v.frame
            let frame = PanicFrame(version: f.version, flags: FrameFlags(rawValue: f.flags), kind: f.kind, hops: f.hops,
                                   frameId: f.frameId, alertId: f.alertId, deviceId: f.deviceId, timestamp: f.timestamp,
                                   latE7: f.latE7, lonE7: f.lonE7, accuracyM: f.accuracyM, batteryPct: f.batteryPct)
            let bytes = FrameCodec.encode(frame, signer: signer)
            XCTAssertEqual(FrameCodec.toBase64Url(bytes), v.base64url, v.name)
            XCTAssertEqual(FrameCodec.toSms(bytes), v.sms)
            XCTAssertEqual(try FrameCodec.decode(FrameCodec.fromBase64Url(v.base64url)), frame)
            XCTAssertTrue(FrameCodec.verify(bytes, signer: signer))
            XCTAssertLessThanOrEqual(v.sms.count, 160)
        }
    }

    func testHopsMayChangeButNothingElse() throws {
        let bytes = try FrameCodec.fromBase64Url(vectors.vectors[0].base64url)
        XCTAssertTrue(FrameCodec.verify(FrameCodec.withHops(bytes, 6), signer: signer))
        var tampered = [UInt8](bytes)
        tampered[60] ^= 1
        XCTAssertFalse(FrameCodec.verify(Data(tampered), signer: signer))
        XCTAssertFalse(FrameCodec.verify(bytes, signer: SymmetricKeySigner(secret: Data(count: 32))))
    }

    func testSplitJoin() {
        let a = Data(repeating: 1, count: 83), b = Data(repeating: 2, count: 83)
        let parts = FrameCodec.split(FrameCodec.join([a, b]) + Data([9, 9]))
        XCTAssertEqual(parts, [a, b])
        XCTAssertEqual(FrameCodec.split(Data([1, 2])), [])
    }
}

final class RelayAndOutboxTests: XCTestCase {
    private let signer = SymmetricKeySigner(secret: Data((0..<32).map { UInt8($0) }))

    private func frame(hops: UInt8 = 0) -> Data {
        FrameCodec.encode(PanicFrame(flags: .hasLocation, kind: 0, hops: hops, frameId: UUID(), alertId: UUID(),
                                     deviceId: UUID(), timestamp: 1_800_000_000, latE7: 1, lonE7: 2), signer: signer)
    }

    func testRelayPolicy() {
        let policy = RelayPolicy(seen: SeenCache())
        let f = frame(hops: 2)
        guard case let .relay(out) = policy.onFrameFromPeer(f) else { return XCTFail("expected relay") }
        XCTAssertEqual(FrameCodec.hops(out), 3)
        XCTAssertTrue(FrameCodec.verify(out, signer: signer))
        XCTAssertEqual(policy.onFrameFromPeer(f), .drop("already seen"))
        XCTAssertEqual(policy.onFrameFromPeer(frame(hops: FrameCodec.maxHops)), .drop("hop limit"))
        XCTAssertEqual(policy.onFrameFromPeer(Data(count: 10)), .drop("malformed"))
    }

    func testOutboxPersistsAndPrunes() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".json")
        var now: Int64 = 1_000
        let box = Outbox(url: url) { now }
        let a = frame(), b = frame()
        XCTAssertTrue(box.add(a, own: true))
        XCTAssertFalse(box.add(a, own: true))
        box.add(b, own: false)
        box.markConfirmed([FrameCodec.frameId(a)])

        let reloaded = Outbox(url: url) { now }
        XCTAssertEqual(reloaded.count, 2)
        XCTAssertEqual(reloaded.unconfirmed().map(\.frameId), [FrameCodec.frameId(b)])
        now += 25 * 3600
        reloaded.prune()
        XCTAssertEqual(reloaded.count, 1)
        now += 72 * 3600
        reloaded.prune()
        XCTAssertEqual(reloaded.count, 0)
    }

    func testOutboxKeepsShortestPath() {
        let box = Outbox(url: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".json"))
        let f = frame(hops: 4)
        box.add(f, own: false)
        box.add(FrameCodec.withHops(f, 1), own: false)
        XCTAssertEqual(FrameCodec.hops(box.unconfirmed()[0].frame), 1)
    }
}
