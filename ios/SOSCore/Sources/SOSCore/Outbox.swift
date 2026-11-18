import Foundation

/// Durable store-and-forward queue: this phone's own frames and frames relayed for others.
/// Persisted as JSON so it survives the app being suspended, killed or the phone rebooting.
public final class Outbox: @unchecked Sendable {
    public struct Entry: Codable, Equatable, Sendable {
        public var frame: Data
        /// True for frames this phone created; false for frames relayed for someone else.
        public var own: Bool
        public var addedAt: Int64
        /// The backend has acknowledged it (accepted, duplicate or permanently rejected).
        public var confirmed = false
        public var smsSent = false
        public var frameId: UUID { FrameCodec.frameId(frame) }

        public init(frame: Data, own: Bool, addedAt: Int64, confirmed: Bool = false, smsSent: Bool = false) {
            self.frame = frame
            self.own = own
            self.addedAt = addedAt
            self.confirmed = confirmed
            self.smsSent = smsSent
        }
    }

    private let url: URL
    private let clock: () -> Int64
    private var entries: [Entry] = []
    private let lock = NSLock()

    public init(url: URL, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }) {
        self.url = url
        self.clock = clock
        if let data = try? Data(contentsOf: url), let saved = try? JSONDecoder().decode([Entry].self, from: data) {
            entries = saved.filter { $0.frame.count == FrameCodec.frameLength }
        }
    }

    /// Adds a frame; returns false if already queued (keeping the copy with fewer hops).
    @discardableResult
    public func add(_ frame: Data, own: Bool) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let id = FrameCodec.frameId(frame)
        if let i = entries.firstIndex(where: { $0.frameId == id }) {
            if FrameCodec.hops(frame) < FrameCodec.hops(entries[i].frame) {
                entries[i].frame = frame
                save()
            }
            return false
        }
        entries.append(Entry(frame: frame, own: own, addedAt: clock()))
        save()
        return true
    }

    public func unconfirmed() -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        return entries.filter { !$0.confirmed }
    }

    public func entry(_ id: UUID) -> Entry? {
        lock.lock(); defer { lock.unlock() }
        return entries.first { $0.frameId == id }
    }

    public var count: Int {
        lock.lock(); defer { lock.unlock() }
        return entries.count
    }

    public func markConfirmed(_ ids: [UUID]) { update(ids) { $0.confirmed = true } }
    public func markSmsSent(_ ids: [UUID]) { update(ids) { $0.smsSent = true } }

    /// Drops confirmed frames after a day and anything older than 72 h (the backend rejects it anyway).
    public func prune(confirmedTtl: Int64 = 24 * 3600, maxAge: Int64 = 72 * 3600) {
        lock.lock(); defer { lock.unlock() }
        let now = clock()
        let before = entries.count
        entries.removeAll { ($0.confirmed && now - $0.addedAt > confirmedTtl) || now - $0.addedAt > maxAge }
        if entries.count != before { save() }
    }

    private func update(_ ids: [UUID], _ fn: (inout Entry) -> Void) {
        lock.lock(); defer { lock.unlock() }
        let set = Set(ids)
        var changed = false
        for i in entries.indices where set.contains(entries[i].frameId) {
            fn(&entries[i])
            changed = true
        }
        if changed { save() }
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(entries) else { return }
        #if os(iOS)
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try? data.write(to: url, options: .atomic)
        #endif
    }
}
