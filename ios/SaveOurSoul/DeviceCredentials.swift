import CryptoKit
import Foundation
import Security
import SOSCore

/// Device identity issued by the backend. The HMAC secret lives in the Keychain, readable
/// only on this device after first unlock (so a locked phone in a pocket can still send SOS).
final class DeviceCredentials {
    private let service = "org.saveoursoul.device"
    private let idKey = "device_id"

    private(set) var deviceId: UUID?
    private var key: SymmetricKey?

    init() { load() }

    var isEnrolled: Bool { deviceId != nil && key != nil }

    var signer: FrameSigner? { key.map { KeySigner(key: $0) } }

    func store(deviceId: UUID, secret: Data) throws {
        clear()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: deviceId.uuidString,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: secret,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        UserDefaults.standard.set(deviceId.uuidString, forKey: idKey)
        load()
    }

    func clear() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
        UserDefaults.standard.removeObject(forKey: idKey)
        deviceId = nil
        key = nil
    }

    private func load() {
        guard let idString = UserDefaults.standard.string(forKey: idKey), let id = UUID(uuidString: idString) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: idString,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let secret = out as? Data else { return }
        deviceId = id
        key = SymmetricKey(data: secret)
    }

    private struct KeySigner: FrameSigner {
        let key: SymmetricKey
        func hmacSHA256(_ data: Data) -> Data { Data(HMAC<SHA256>.authenticationCode(for: data, using: key)) }
    }
}
