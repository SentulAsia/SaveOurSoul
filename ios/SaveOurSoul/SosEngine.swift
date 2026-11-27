import CoreLocation
import Foundation
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
///  2. Internet (Wi-Fi, or cellular through Wi-Fi Assist) and the Bluetooth LE relay fire at once.
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
    @Published private(set) var ble = ChannelState()
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
    private let seen = SeenCache()
    private lazy var relayPolicy = RelayPolicy(seen: seen)
    private let outbox: Outbox
    private let pathMonitor = NWPathMonitor()
    private var online = false
    private let ackLedger = AckLedger()
    private let rotation = OutboxRotation()
    /// Crowd-grade Bluetooth LE transport shared with Android (docs/BLE.md).
    private lazy var bleTransport = BleTransport(host: self, namespace: AppEnvironment.meshNamespace)
    private var activeLoop: Task<Void, Never>?
    private var flushing = false
    private var smsQueue: [SmsRequest] = []
    private var lastGatewaySmsAt = Date.distantPast
    /** Analytics milestones already reported for the active alert, so each is counted once. */
    private var reported: Set<String> = []

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        outbox = Outbox(url: dir.appendingPathComponent("outbox.json"))
        enrolled = credentials.isEnrolled


        // Retry the moment any network becomes usable.
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in
                self?.online = up
                if up { await self?.flush() }
            }
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

        // Start radios now: iOS may launch us in the background to restore Bluetooth sessions.
        updateMeshRunning(foreground: UIApplication.shared.applicationState == .active)

        // Keep the BLE beacon (own SOS / holds frames / has internet) current.
        Task { [weak self] in
            while let self {
                self.bleTransport.refresh()
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
    }

    private var now: UInt32 { UInt32(Date().timeIntervalSince1970) }

    // MARK: - app lifecycle

    func onForeground() {
        location.requestPermission()
        updateMeshRunning(foreground: true)
        Task { await flush() }
    }

    func onBackground() {
        // BLE keeps relaying in the background; only the screen-awake setting changes.
        updateMeshRunning(foreground: false)
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
        reported.removeAll()
        AppAnalytics.event("sos_triggered", ["kind": String(describing: kind), "registered": credentials.isEnrolled])
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
        AppAnalytics.event("sos_cancelled", ["time_bucket": AppAnalytics.timeBucket(sinceActive)])
        settings.activeAlertId = nil
        activeLoop?.cancel()
        activeAlertId = nil
        responderStatus = "cancelled"
        location.stopSosTracking()
        updateMeshRunning()
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
        if status.status == "acknowledged" || status.status == "resolved" { milestone("sos_\(status.status)") }
        if status.status == "resolved", settings.activeAlertId == alertId {
            settings.activeAlertId = nil
            activeLoop?.cancel()
            activeAlertId = nil
            location.stopSosTracking()
            updateMeshRunning()
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
        if !own.isEmpty { await upload(own, channel: "internet", relayId: nil, ownFrames: true) }
        if !relayed.isEmpty {
            await upload(relayed, channel: "mesh", relayId: credentials.deviceId?.uuidString.lowercased(), ownFrames: false)
        }
        offerGatewaySms()
        refreshPending()
    }

    private func upload(_ entries: [Outbox.Entry], channel: String, relayId: String?, ownFrames: Bool) async {
        if ownFrames { internet = ChannelState(health: .working, detail: "sending…") }
        do {
            let results = try await backend.postSignals(entries.map(\.frame), channel: channel, relayDeviceId: relayId)
            outbox.markConfirmed(zip(entries, results).filter { $0.1.terminal }.map { $0.0.frameId })
            // Keep receipts so nearby phones still carrying these frames learn they can stop.
            for (e, r) in zip(entries, results) {
                if let r64 = r.receipt, let receipt = try? FrameCodec.fromBase64Url(r64) {
                    ackLedger.add(Ack(frameId: e.frameId, receipt: receipt))
                }
            }
            guard ownFrames else { return }
            if let rejected = results.first(where: { $0.status == "rejected" }) {
                internet = ChannelState(health: .failed, detail: "server rejected: \(rejected.reason ?? "?")")
                error = "The server rejected the signal (\(rejected.reason ?? "?")). Re-register this phone."
            } else {
                internet = ChannelState(health: .ok, detail: "delivered to server")
                if settings.activeAlertId != nil { milestone("sos_delivered", ["channel": "internet"]) }
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
            if sent {
                outbox.markSmsSent(ids)
                AppAnalytics.event("sos_sms_sent", ["to": "gateway"])
            }
            sms = ChannelState(health: sent ? .ok : .failed, detail: sent ? "sent to SMS gateway" : "SMS not sent")
        case .contacts:
            if sent { AppAnalytics.event("sos_sms_sent", ["to": "contacts"]) }
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

    private var sinceActive: TimeInterval { Date().timeIntervalSince(settings.activeSince ?? Date()) }

    /// Reports an SOS milestone once per alert, with the time since the SOS started.
    private func milestone(_ event: String, _ params: [String: Any] = [:]) {
        guard reported.insert(event).inserted else { return }
        var p = params
        p["kind"] = String(describing: settings.activeKind)
        p["time_bucket"] = AppAnalytics.timeBucket(sinceActive)
        AppAnalytics.event(event, p)
    }

    /// Frames from a nearby phone over Bluetooth LE: queue, pass on, upload.
    private func onFramesFromPeers(_ frames: [Data], source: String) {
        let fresh = frames.compactMap { raw -> Data? in
            guard case let .relay(frame) = relayPolicy.onFrameFromPeer(raw) else { return nil }
            return outbox.add(frame, own: false) ? frame : nil
        }
        guard !fresh.isEmpty else { return }
        relayedForOthers += fresh.count
        AppAnalytics.event("relay_carried", ["frames": fresh.count, "via": String(source.prefix { $0 != ":" })])
        refreshPending()
        bleTransport.refresh()
        Task { await flush() }
    }

    /// Receipts from nearby phones. Our own frames count as delivered only if the receipt
    /// verifies with our key, so a fake "delivered" cannot silence an SOS.
    private func onAcksFromPeers(_ incoming: [Ack]) {
        let signer = credentials.signer
        let accepted = incoming.filter { ack in
            guard let e = outbox.entry(ack.frameId), !e.confirmed else { return false }
            if !e.own { return true }
            guard let signer else { return false }
            return Receipts.verify(ack, signer: signer)
        }
        guard !accepted.isEmpty else { return }
        outbox.markConfirmed(accepted.map(\.frameId))
        accepted.forEach(ackLedger.add)
        if accepted.contains(where: { outbox.entry($0.frameId)?.own == true }) {
            if settings.activeAlertId != nil { milestone("sos_delivered", ["channel": "bluetooth"]) }
            ble = ChannelState(health: .ok, detail: "delivered through a nearby phone")
        }
        refreshPending()
        bleTransport.refresh()
    }

    func updateMeshRunning(foreground: Bool = true) {
        // BLE keeps working in the background (bluetooth-central/peripheral modes), so relay mode
        // keeps it on even when the app is not open.
        if isActive || settings.relayEnabled {
            bleTransport.start()
            if ble.health != .ok {
                ble = ChannelState(health: bleTransport.bluetoothOn ? .working : .failed,
                                   detail: bleTransport.bluetoothOn ? "listening for nearby phones" : "turn on Bluetooth")
            }
        } else {
            bleTransport.stop()
            ble = ChannelState(health: .idle, detail: "off")
        }
        // Android phones only see an iPhone's BLE advert while the app is in the foreground:
        // keep the screen awake while an SOS is active and the app is open.
        UIApplication.shared.isIdleTimerDisabled = isActive && foreground
    }

    private func refreshPending() { pendingFrames = outbox.unconfirmed().count }
}

extension SosEngine: BleHost {
    func beacon() -> BleProtocol.Beacon {
        let pending = outbox.unconfirmed()
        var flags: UInt8 = 0
        if settings.activeAlertId != nil { flags |= BleProtocol.flagOwnSos }
        if online { flags |= BleProtocol.flagHasInternet }
        if !pending.isEmpty { flags |= BleProtocol.flagHoldsFrames }
        return BleProtocol.Beacon(flags: flags, digest: BleProtocol.digest(pending.map(\.frameId)))
    }

    func localState() -> ContactPolicy.Local {
        ContactPolicy.Local(holdsUndelivered: !outbox.unconfirmed().isEmpty, hasInternet: online)
    }

    func framesToServe() -> [Data] { rotation.pick(outbox.unconfirmed()) }

    func framesToPush() -> [Data] {
        outbox.unconfirmed().sorted { $0.own && !$1.own }.prefix(BleProtocol.maxFramesPerValue).map(\.frame)
    }

    func recentAcks() -> [Ack] { ackLedger.recent(BleProtocol.maxAcksPerValue) }

    func onFramesReceived(_ frames: [Data], source: String) { onFramesFromPeers(frames, source: source) }

    func onAcksReceived(_ acks: [Ack]) { onAcksFromPeers(acks) }

    func uploadPulled(_ frames: [Data]) async -> [Ack] {
        guard online else { return [] }
        await flush()
        let ids = Set(frames.map(FrameCodec.frameId))
        return ackLedger.recent(64).filter { ids.contains($0.frameId) }
    }
}
