import CoreLocation
import Foundation
import NearbyConnections
import Network
import SOSCore
import UIKit

/// A pre-filled Messages sheet. iOS never lets apps send SMS silently, so the user taps Send.
struct SmsRequest: Identifiable {
    enum Purpose { case gateway([UUID]), contacts }
    let id = UUID()
    let recipients: [String]
    let body: String
    let purpose: Purpose

    var isGateway: Bool {
        if case .gateway = purpose { return true }
        return false
    }
}

/// Sends an SOS over every channel the iPhone has, the same way as the Android app:
///
///  1. The frame goes into a durable outbox before anything else.
///  2. Internet (Wi-Fi, or cellular through Wi-Fi Assist) and the Nearby mesh fire at once.
///  3. Contacts get a Messages sheet with a map link; if the backend hasn't confirmed within
///     the SMS delay, a second sheet sends the signed frame to the SMS gateway.
///  4. While active, location frames go out every 60 s and everything unconfirmed is retried,
///     until a responder resolves the alert or the user cancels.
@MainActor
final class SosEngine: ObservableObject {
    static let shared = SosEngine()

    enum Health { case idle, working, ok, failed }

    struct ChannelState: Equatable {
        var health: Health = .idle
        var detail = ""
    }

    @Published private(set) var activeAlertId: UUID?
    @Published private(set) var kind: SosKind = .general
    @Published private(set) var internet = ChannelState()
    @Published private(set) var sms = ChannelState()
    @Published private(set) var mesh = ChannelState()
    @Published private(set) var responderStatus: String?
    @Published private(set) var lastFix: CLLocation?
    @Published private(set) var relayedForOthers = 0
    @Published private(set) var pendingFrames = 0
    @Published var error: String?
    @Published var smsRequest: SmsRequest?
    @Published private(set) var enrolled = false

    var isActive: Bool { activeAlertId != nil }

    let settings = Settings()
    let credentials = DeviceCredentials()
    private(set) lazy var backend = BackendClient { [settings] in settings.backendUrl }
    private let location = LocationProvider()
    private let meshTransport = MeshTransport()
    private let seen = SeenCache()
    private lazy var relayPolicy = RelayPolicy(seen: seen)
    private let outbox: Outbox
    private let pathMonitor = NWPathMonitor()
    private var activeLoop: Task<Void, Never>?
    private var flushing = false
    private var smsQueue: [SmsRequest] = []
    private var lastGatewaySmsAt = Date.distantPast

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        outbox = Outbox(url: dir.appendingPathComponent("outbox.json"))
        enrolled = credentials.isEnrolled

        meshTransport.onPayload = { [weak self] id, data in self?.onPeerPayload(id, data) }
        meshTransport.framesToShare = { [weak self] in self?.outbox.unconfirmed().map(\.frame) ?? [] }
        meshTransport.onPeersChanged = { [weak self] n in self?.updateMeshState(peers: n) }

        // Retry the moment any network becomes usable.
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in await self?.flush() }
        }
        pathMonitor.start(queue: .global(qos: .userInitiated))

        // Resume an SOS that was active when the app was killed.
        if let id = settings.activeAlertId {
            activeAlertId = id
            kind = settings.activeKind
            location.startSosTracking()
            startActiveLoop(id)
        }
        refreshPending()
    }

    private var now: UInt32 { UInt32(Date().timeIntervalSince1970) }

    // MARK: - app lifecycle

    func onForeground() {
        location.requestPermission()
        updateMeshRunning(foreground: true)
        Task { await flush() }
    }

    func onBackground() {
        // Without an active SOS iOS suspends us shortly; stop radios cleanly instead of being cut off.
        if !isActive { updateMeshRunning(foreground: false) }
    }

    func didEnroll(deviceId: UUID, secret: Data) throws {
        try credentials.store(deviceId: deviceId, secret: secret)
        enrolled = true
    }

    // MARK: - user actions

    func trigger(kind: SosKind, test: Bool = false) {
        guard settings.activeAlertId == nil else { return }
        if !credentials.isEnrolled {
            error = "This phone isn't registered with the SOS server, so only your contacts can be texted."
        }
        let alertId = UUID()
        settings.activeAlertId = alertId
        settings.activeKind = kind
        settings.activeSince = Date()
        activeAlertId = alertId
        self.kind = kind
        responderStatus = nil
        internet = ChannelState(health: .working, detail: "sending…")
        location.startSosTracking()
        updateMeshRunning(foreground: true)
        UINotificationFeedbackGenerator().notificationOccurred(.warning)

        // First frame uses whatever location is cached: never wait for GPS.
        emitFrame(alertId: alertId, kind: kind, fix: location.quickFix, cancel: false, test: test)
        textContacts(alertId: alertId, kind: kind, cancel: false)
        startActiveLoop(alertId)
    }

    func cancel() {
        guard let alertId = settings.activeAlertId else { return }
        let kind = settings.activeKind
        settings.activeAlertId = nil
        activeLoop?.cancel()
        activeAlertId = nil
        responderStatus = "cancelled"
        location.stopSosTracking()
        emitFrame(alertId: alertId, kind: kind, fix: nil, cancel: true)
        textContacts(alertId: alertId, kind: kind, cancel: true)
    }

    // MARK: - frames

    @discardableResult
    private func emitFrame(alertId: UUID, kind: SosKind, fix: CLLocation?, cancel: Bool, test: Bool = false) -> Data? {
        guard let deviceId = credentials.deviceId, let signer = credentials.signer else { return nil }
        var flags: FrameFlags = []
        if fix != nil { flags.insert(.hasLocation) }
        if cancel { flags.insert(.cancel) }
        if test { flags.insert(.test) }
        let frame = PanicFrame(
            flags: flags, kind: kind.rawValue, frameId: UUID(), alertId: alertId, deviceId: deviceId, timestamp: now,
            latE7: fix.map { Int32(($0.coordinate.latitude * 1e7).rounded()) } ?? 0,
            lonE7: fix.map { Int32(($0.coordinate.longitude * 1e7).rounded()) } ?? 0,
            accuracyM: fix.flatMap { $0.horizontalAccuracy >= 0 ? UInt16(min($0.horizontalAccuracy, 65534)) : nil } ?? PanicFrame.unknownAccuracy,
            batteryPct: location.batteryPct ?? PanicFrame.unknownBattery
        )
        let bytes = FrameCodec.encode(frame, signer: signer)
        _ = seen.firstSeen(frame.frameId) // ignore our own frame when peers echo it back
        outbox.add(bytes, own: true)
        if let fix { lastFix = fix }
        meshTransport.broadcast([bytes])
        refreshPending()
        Task { await flush() }
        return bytes
    }

    private func startActiveLoop(_ alertId: UUID) {
        activeLoop?.cancel()
        activeLoop = Task { [weak self] in
            var lastLocationFrame = Date.distantPast
            var tick = 0
            while !Task.isCancelled {
                guard let self, self.settings.activeAlertId == alertId else { return }
                if Date().timeIntervalSince(lastLocationFrame) >= 60 {
                    lastLocationFrame = Date()
                    let fix = await self.location.bestFix()
                    self.emitFrame(alertId: alertId, kind: self.settings.activeKind, fix: fix, cancel: false)
                }
                await self.flush()
                if tick % 2 == 0 { await self.pollResponder(alertId) }
                tick += 1
                try? await Task.sleep(nanoseconds: 10_000_000_000)
            }
        }
    }

    private func pollResponder(_ alertId: UUID) async {
        guard let status = await backend.alertStatus(alertId) else { return }
        responderStatus = status.status
        if status.status == "resolved", settings.activeAlertId == alertId {
            settings.activeAlertId = nil
            activeLoop?.cancel()
            activeAlertId = nil
            location.stopSosTracking()
        }
    }

    // MARK: - delivery

    /// Uploads everything unconfirmed and offers the SMS gateway for own frames that are overdue.
    func flush() async {
        guard !flushing else { return }
        flushing = true
        defer { flushing = false }
        outbox.prune()
        let pending = outbox.unconfirmed()
        let own = pending.filter(\.own)
        let relayed = pending.filter { !$0.own }
        if !own.isEmpty { await upload(own, channel: "internet", relayId: nil) }
        if !relayed.isEmpty { await upload(relayed, channel: "mesh", relayId: credentials.deviceId?.uuidString.lowercased()) }
        offerGatewaySms()
        refreshPending()
    }

    private func upload(_ entries: [Outbox.Entry], channel: String, relayId: String?) async {
        let ownFrames = relayId == nil
        if ownFrames { internet = ChannelState(health: .working, detail: "sending…") }
        do {
            let results = try await backend.postSignals(entries.map(\.frame), channel: channel, relayDeviceId: relayId)
            outbox.markConfirmed(zip(entries, results).filter { $0.1.terminal }.map { $0.0.frameId })
            guard ownFrames else { return }
            if let rejected = results.first(where: { $0.status == "rejected" }) {
                internet = ChannelState(health: .failed, detail: "server rejected: \(rejected.reason ?? "?")")
                error = "The server rejected the signal (\(rejected.reason ?? "?")). Re-register this phone."
            } else {
                internet = ChannelState(health: .ok, detail: "delivered to server")
            }
            if let s = results.last(where: { $0.alertStatus != nil })?.alertStatus { responderStatus = s }
        } catch {
            if ownFrames { internet = ChannelState(health: .failed, detail: "no internet — retrying") }
        }
    }

    private func offerGatewaySms() {
        let gateway = settings.smsGatewayNumber
        guard !gateway.isEmpty else { return }
        let nowSec = Int64(now)
        let due = outbox.unconfirmed().filter { e in
            guard e.own, !e.smsSent, let f = try? FrameCodec.decode(e.frame) else { return false }
            return f.isCancel || nowSec - e.addedAt >= Int64(settings.smsFallbackDelaySec)
        }
        guard let newest = due.max(by: { $0.addedAt < $1.addedAt }) else { return }
        let hasCancel = due.contains { (try? FrameCodec.decode($0.frame))?.isCancel == true }
        // Don't nag: at most one gateway sheet every 2 minutes unless it carries a cancel.
        guard hasCancel || Date().timeIntervalSince(lastGatewaySmsAt) >= 120 else { return }
        guard !smsQueue.contains(where: \.isGateway), smsRequest?.isGateway != true else { return }
        lastGatewaySmsAt = Date()
        // The backend extracts every SOS1: token, so one message can carry several frames.
        let frames = (due.filter { (try? FrameCodec.decode($0.frame))?.isCancel == true } + [newest])
        var seenIds = Set<UUID>()
        let body = frames.filter { seenIds.insert($0.frameId).inserted }.map { FrameCodec.toSms($0.frame) }.joined(separator: "\n")
        sms = ChannelState(health: .working, detail: "tap Send to text the SMS gateway")
        enqueueSms(SmsRequest(recipients: [gateway], body: body, purpose: .gateway(due.map(\.frameId))))
    }

    private func textContacts(alertId: UUID, kind: SosKind, cancel: Bool) {
        let contacts = settings.emergencyContacts
        guard !contacts.isEmpty else { return }
        let name = settings.userName.isEmpty ? "a SaveOurSoul user" : settings.userName
        let body: String
        if cancel {
            body = "SaveOurSoul: \(name) is safe now. Earlier SOS cancelled."
        } else {
            let fix = lastFix ?? location.quickFix
            let whereText = fix.map { String(format: " Location: https://maps.apple.com/?q=%.6f,%.6f", $0.coordinate.latitude, $0.coordinate.longitude) }
                ?? " Location unavailable."
            body = "SOS from \(name) (\(String(describing: kind)) emergency).\(whereText) Sent by SaveOurSoul."
        }
        enqueueSms(SmsRequest(recipients: contacts, body: body, purpose: .contacts))
    }

    private func enqueueSms(_ r: SmsRequest) {
        if smsRequest == nil { smsRequest = r } else { smsQueue.append(r) }
    }


    /// Called by the Messages sheet when the user taps Send or Cancel.
    func smsFinished(_ request: SmsRequest, sent: Bool) {
        switch request.purpose {
        case .gateway(let ids):
            if sent { outbox.markSmsSent(ids) }
            sms = ChannelState(health: sent ? .ok : .failed, detail: sent ? "sent to SMS gateway" : "SMS not sent")
        case .contacts:
            sms = ChannelState(health: sent ? .ok : .failed, detail: sent ? "contacts texted" : "contacts not texted")
        }
        smsRequest = nil
    }

    /// Called once the sheet is gone (also after a swipe-down): present the next queued message.
    func smsSheetDismissed() {
        guard smsRequest == nil, !smsQueue.isEmpty else { return }
        let next = smsQueue.removeFirst()
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 400_000_000)
            self.smsRequest = next
        }
    }

    // MARK: - relay

    private func onPeerPayload(_ endpoint: EndpointID, _ payload: Data) {
        let fresh = FrameCodec.split(payload).compactMap { raw -> Data? in
            guard case let .relay(frame) = relayPolicy.onFrameFromPeer(raw) else { return nil }
            return outbox.add(frame, own: false) ? frame : nil
        }
        guard !fresh.isEmpty else { return }
        relayedForOthers += fresh.count
        meshTransport.broadcast(fresh, except: endpoint)
        refreshPending()
        Task { await flush() }
    }

    func updateMeshRunning(foreground: Bool = true) {
        if isActive || (settings.relayEnabled && foreground) { meshTransport.start() } else { meshTransport.stop() }
        updateMeshState(peers: nil)
    }

    private func updateMeshState(peers: Int?) {
        if !meshTransport.running {
            mesh = ChannelState(health: .idle, detail: "off")
        } else if let peers, peers > 0 {
            mesh = ChannelState(health: .ok, detail: "\(peers) phone(s) nearby")
        } else if peers == nil, mesh.health == .ok {
            return
        } else {
            mesh = ChannelState(health: .working, detail: "searching for nearby phones…")
        }
    }

    private func refreshPending() { pendingFrames = outbox.unconfirmed().count }
}
