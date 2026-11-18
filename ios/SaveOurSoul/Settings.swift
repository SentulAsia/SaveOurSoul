import Foundation
import SOSCore

/// User preferences, plus the active alert so an SOS survives the app being killed.
final class Settings {
    private let d = UserDefaults.standard

    var backendUrl: String {
        get { d.string(forKey: "backend_url") ?? "https://sos.example.org" }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")), forKey: "backend_url") }
    }
    var userName: String {
        get { d.string(forKey: "user_name") ?? "" }
        set { d.set(newValue, forKey: "user_name") }
    }
    /// SMS gateway number whose webhook posts to /api/v1/sms/inbound.
    var smsGatewayNumber: String {
        get { d.string(forKey: "sms_gateway") ?? "" }
        set { d.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "sms_gateway") }
    }
    var emergencyContacts: [String] {
        get { (d.string(forKey: "contacts") ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }
        set { d.set(newValue.joined(separator: ","), forKey: "contacts") }
    }
    /// Seconds to wait for an internet confirmation before offering the SMS gateway.
    var smsFallbackDelaySec: Int {
        get { d.object(forKey: "sms_delay") as? Int ?? 15 }
        set { d.set(max(0, newValue), forKey: "sms_delay") }
    }
    /// Listen on Bluetooth / Wi-Fi to relay other people's SOS (while the app is open).
    var relayEnabled: Bool {
        get { d.object(forKey: "relay") as? Bool ?? true }
        set { d.set(newValue, forKey: "relay") }
    }

    var activeAlertId: UUID? {
        get { d.string(forKey: "active_alert").flatMap(UUID.init(uuidString:)) }
        set { d.set(newValue?.uuidString, forKey: "active_alert") }
    }
    var activeKind: SosKind {
        get { SosKind(rawValue: UInt8(d.integer(forKey: "active_kind"))) ?? .general }
        set { d.set(Int(newValue.rawValue), forKey: "active_kind") }
    }
    var activeSince: Date? {
        get { d.object(forKey: "active_since") as? Date }
        set { d.set(newValue, forKey: "active_since") }
    }
}
