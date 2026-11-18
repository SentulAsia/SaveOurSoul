import CoreBluetooth
import Foundation
import SOSCore

/// What the BLE layer needs from the SOS engine.
@MainActor
protocol BleHost: AnyObject {
    func beacon() -> BleProtocol.Beacon
    func localState() -> ContactPolicy.Local
    func framesToServe() -> [Data]
    func framesToPush() -> [Data]
    func recentAcks() -> [Ack]
    func onFramesReceived(_ frames: [Data], source: String)
    func onAcksReceived(_ acks: [Ack])
    /// Upload freshly pulled frames right away if online; returns receipts to hand back.
    func uploadPulled(_ frames: [Data]) async -> [Ack]
}

/// SOS-BLE v1 on iOS (docs/BLE.md). Same GATT service, characteristics and beacon as Android.
///
/// iOS specifics:
///  - Advertising carries the beacon in the local name. In the background iOS strips the name
///    and moves the service UUID to an "overflow" area that only other iPhones can see, so
///    keep the SOS screen open on an iPhone for Android phones to find it. Scanning,
///    connecting and pushing still work in the background, so a backgrounded iPhone victim
///    can still hand its SOS to any nearby phone that advertises internet.
///  - Scanning in the background works only with a service filter (which we use) and without
///    duplicate reports, so contacts are slower than in the foreground.
@MainActor
final class BleTransport: NSObject {
    private weak var host: BleHost?
    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private let policy = ContactPolicy()
    private let serviceUUID = CBUUID(nsuuid: BleProtocol.serviceUUID)
    private let inbox = CBUUID(nsuuid: BleProtocol.inboxUUID)
    private let outbox = CBUUID(nsuuid: BleProtocol.outboxUUID)
    private let acks = CBUUID(nsuuid: BleProtocol.acksUUID)
    private let confirmed = CBUUID(nsuuid: BleProtocol.confirmedUUID)

    private(set) var running = false
    private var serviceAdded = false
    private var advertisedName: String?
    private var sessions: [UUID: GattClient] = [:]
    private var readCache: [String: Data] = [:]
    private static let maxSessions = 3

    init(host: BleHost) {
        self.host = host
        super.init()
        central = CBCentralManager(delegate: self, queue: nil,
                                   options: [CBCentralManagerOptionRestoreIdentifierKey: "org.saveoursoul.central"])
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil,
                                                options: [CBPeripheralManagerOptionRestoreIdentifierKey: "org.saveoursoul.peripheral"])
    }

    var bluetoothOn: Bool { central.state == .poweredOn }

    func start() {
        running = true
        refresh()
    }

    func stop() {
        running = false
        if central.state == .poweredOn { central.stopScan() }
        if peripheralManager.state == .poweredOn { peripheralManager.stopAdvertising() }
        advertisedName = nil
        sessions.values.forEach { central.cancelPeripheralConnection($0.peripheral) }
        sessions.removeAll()
    }

    /// Re-advertise if the beacon changed; (re)start scanning. Call every few seconds.
    func refresh() {
        guard running, let host else { return }
        if peripheralManager.state == .poweredOn {
            addServiceIfNeeded()
            let name = host.beacon().localName
            if name != advertisedName {
                peripheralManager.stopAdvertising()
                peripheralManager.startAdvertising([
                    CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
                    CBAdvertisementDataLocalNameKey: name,
                ])
                advertisedName = name
            }
        }
        if central.state == .poweredOn, !central.isScanning {
            central.scanForPeripherals(withServices: [serviceUUID],
                                       options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        }
    }

    private func addServiceIfNeeded() {
        guard !serviceAdded else { return }
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [
            CBMutableCharacteristic(type: inbox, properties: [.write], value: nil, permissions: [.writeable]),
            CBMutableCharacteristic(type: outbox, properties: [.read], value: nil, permissions: [.readable]),
            CBMutableCharacteristic(type: acks, properties: [.write], value: nil, permissions: [.writeable]),
            CBMutableCharacteristic(type: confirmed, properties: [.read], value: nil, permissions: [.readable]),
        ]
        peripheralManager.add(service)
        serviceAdded = true
    }

    // MARK: - central session

    private func consider(_ peripheral: CBPeripheral, advertisement: [String: Any], rssi: Int) {
        guard let host, sessions.count < Self.maxSessions, sessions[peripheral.identifier] == nil else { return }
        let serviceData = (advertisement[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[serviceUUID]
        let beacon = BleProtocol.Beacon.from(serviceData: serviceData)
            ?? BleProtocol.Beacon.from(localName: advertisement[CBAdvertisementDataLocalNameKey] as? String)
        let action = policy.decide(peerKey: peripheral.identifier.uuidString, beacon: beacon, rssi: rssi,
                                   local: host.localState(), now: Date().timeIntervalSince1970)
        guard action != .none else { return }
        let client = GattClient(peripheral: peripheral, uuids: (serviceUUID, inbox, outbox, acks, confirmed))
        sessions[peripheral.identifier] = client
        central.connect(peripheral)
        // Watchdog: a peer that stops answering mid-session is cut off, which fails pending operations.
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 25_000_000_000)
            if self.sessions[peripheral.identifier] === client { self.central.cancelPeripheralConnection(peripheral) }
        }
        Task { @MainActor in
            await self.run(client, action: action)
            self.central.cancelPeripheralConnection(peripheral)
            self.sessions[peripheral.identifier] = nil
        }
    }

    private func run(_ c: GattClient, action: ContactPolicy.Action) async {
        guard let host else { return }
        let key = "ble:" + c.peripheral.identifier.uuidString
        do {
            try await c.waitConnected(timeout: 8)
            try await c.discover()
            let push = action == .push || action == .exchange
            let pull = action == .pull || action == .exchange
            if push {
                let frames = host.framesToPush()
                if !frames.isEmpty { try await c.write(.inbox, FrameCodec.join(frames)) }
            }
            if pull {
                let frames = FrameCodec.split(try await c.read(.outbox))
                if !frames.isEmpty {
                    host.onFramesReceived(frames, source: key)
                    let receipts = await host.uploadPulled(frames)
                    if !receipts.isEmpty {
                        try await c.write(.acks, Receipts.encode(Array(receipts.prefix(BleProtocol.maxAcksPerValue))))
                    }
                }
            }
            // Let an online peer upload what we pushed, then collect its receipts.
            if push { try? await Task.sleep(nanoseconds: 4_000_000_000) }
            host.onAcksReceived(Receipts.decode(try await c.read(.confirmed)))
        } catch {
            // Peer walked away or refused: normal in a crowd.
        }
    }

    // MARK: - peripheral helpers

    private func readValue(for uuid: CBUUID, central: CBCentral, offset: Int) -> Data {
        let key = central.identifier.uuidString + uuid.uuidString
        if offset == 0 || readCache[key] == nil {
            let value: Data
            switch uuid {
            case outbox: value = FrameCodec.join(host?.framesToServe() ?? [])
            case confirmed: value = Receipts.encode(Array((host?.recentAcks() ?? []).prefix(BleProtocol.maxAcksPerValue)))
            default: value = Data()
            }
            readCache[key] = value
            if readCache.count > 256 { readCache.removeAll() }
        }
        return readCache[key] ?? Data()
    }

    private func handleWrite(_ uuid: CBUUID, _ value: Data, from central: CBCentral) {
        switch uuid {
        case inbox:
            let frames = FrameCodec.split(value)
            if !frames.isEmpty { host?.onFramesReceived(frames, source: "ble:" + central.identifier.uuidString) }
        case acks:
            let a = Receipts.decode(value)
            if !a.isEmpty { host?.onAcksReceived(a) }
        default: break
        }
    }
}

extension BleTransport: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in self.refresh() }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {}

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let rssi = RSSI.intValue
        Task { @MainActor in self.consider(peripheral, advertisement: advertisementData, rssi: rssi == 127 ? -100 : rssi) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in self.sessions[peripheral.identifier]?.connected(error: nil) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in self.sessions[peripheral.identifier]?.connected(error: error ?? CBError(.unknown)) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in self.sessions[peripheral.identifier]?.failAll(error ?? CBError(.peripheralDisconnected)) }
    }
}

extension BleTransport: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            if peripheral.state != .poweredOn {
                self.serviceAdded = false
                self.advertisedName = nil
            }
            self.refresh()
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {}

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        Task { @MainActor in
            let value = self.readValue(for: request.characteristic.uuid, central: request.central, offset: request.offset)
            guard request.offset <= value.count else {
                return peripheral.respond(to: request, withResult: .invalidOffset)
            }
            request.value = value.subdata(in: request.offset..<value.count)
            peripheral.respond(to: request, withResult: .success)
        }
    }

    nonisolated func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        Task { @MainActor in
            // Long writes arrive as several requests with offsets: assemble per characteristic.
            var assembled: [CBUUID: Data] = [:]
            for r in requests {
                guard let v = r.value, r.offset + v.count <= BleProtocol.maxValue else {
                    return peripheral.respond(to: requests[0], withResult: .invalidAttributeValueLength)
                }
                var buf = assembled[r.characteristic.uuid] ?? Data()
                if buf.count < r.offset + v.count { buf.append(Data(count: r.offset + v.count - buf.count)) }
                buf.replaceSubrange(r.offset..<r.offset + v.count, with: v)
                assembled[r.characteristic.uuid] = buf
            }
            if let first = requests.first {
                for (uuid, data) in assembled { self.handleWrite(uuid, data, from: first.central) }
                peripheral.respond(to: first, withResult: .success)
            }
        }
    }
}

/// One short GATT client connection driven with async/await.
@MainActor
private final class GattClient: NSObject, CBPeripheralDelegate {
    enum Char { case inbox, outbox, acks, confirmed }

    let peripheral: CBPeripheral
    private let uuids: (service: CBUUID, inbox: CBUUID, outbox: CBUUID, acks: CBUUID, confirmed: CBUUID)
    private var chars: [CBUUID: CBCharacteristic] = [:]
    private var connectWaiter: CheckedContinuation<Void, Error>?
    private var isConnected = false
    private var pending: CheckedContinuation<Data, Error>?

    init(peripheral: CBPeripheral, uuids: (CBUUID, CBUUID, CBUUID, CBUUID, CBUUID)) {
        self.peripheral = peripheral
        self.uuids = uuids
        super.init()
        peripheral.delegate = self
    }

    func connected(error: Error?) {
        isConnected = error == nil
        if let error { connectWaiter?.resume(throwing: error) } else { connectWaiter?.resume() }
        connectWaiter = nil
    }

    func failAll(_ error: Error) {
        connectWaiter?.resume(throwing: error)
        connectWaiter = nil
        pending?.resume(throwing: error)
        pending = nil
    }

    func waitConnected(timeout: TimeInterval) async throws {
        if isConnected { return }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1e9))
            // Only gives up on the connect itself; later operations are not affected.
            self.connectWaiter?.resume(throwing: CBError(.connectionTimeout))
            self.connectWaiter = nil
        }
        try await withCheckedThrowingContinuation { connectWaiter = $0 }
    }

    func discover() async throws {
        _ = try await op { self.peripheral.discoverServices([self.uuids.service]) }
        guard let service = peripheral.services?.first(where: { $0.uuid == uuids.service }) else { throw CBError(.unknown) }
        _ = try await op { self.peripheral.discoverCharacteristics(nil, for: service) }
        service.characteristics?.forEach { chars[$0.uuid] = $0 }
    }

    func read(_ c: Char) async throws -> Data {
        let ch = try characteristic(c)
        return try await op { self.peripheral.readValue(for: ch) }
    }

    func write(_ c: Char, _ value: Data) async throws {
        let ch = try characteristic(c)
        _ = try await op { self.peripheral.writeValue(value, for: ch, type: .withResponse) }
    }

    private func characteristic(_ c: Char) throws -> CBCharacteristic {
        let uuid: CBUUID
        switch c {
        case .inbox: uuid = uuids.inbox
        case .outbox: uuid = uuids.outbox
        case .acks: uuid = uuids.acks
        case .confirmed: uuid = uuids.confirmed
        }
        guard let ch = chars[uuid] else { throw CBError(.unknown) }
        return ch
    }

    private func op(_ start: @escaping () -> Void) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            pending = cont
            start()
        }
    }

    private func finish(_ data: Data, _ error: Error?) {
        if let error { pending?.resume(throwing: error) } else { pending?.resume(returning: data) }
        pending = nil
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in self.finish(Data(), error) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        Task { @MainActor in self.finish(Data(), error) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        let value = characteristic.value ?? Data()
        Task { @MainActor in self.finish(value, error) }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in self.finish(Data(), error) }
    }
}
