import Foundation
import SOSCore

struct BackendError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// HTTPS client for the SOS backend. iOS "Wi-Fi Assist" moves requests to cellular on its own
/// when the Wi-Fi network has no internet, so one URLSession covers both.
final class BackendClient {
    struct FrameResult: Decodable {
        let status: String
        let frameId: UUID?
        let reason: String?
        let alertStatus: String?
        /// The backend has the frame, or will never accept it: stop retrying.
        var terminal: Bool { ["accepted", "duplicate", "rejected"].contains(status) }
    }

    struct AlertStatus: Decodable {
        let status: String
        let channels: [String]?
    }

    private let baseUrl: () -> String
    private let session: URLSession

    init(baseUrl: @escaping () -> String) {
        self.baseUrl = baseUrl
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 10
        cfg.waitsForConnectivity = false // fail fast; the engine retries and uses other channels
        cfg.allowsConstrainedNetworkAccess = true // Low Data Mode must not block an SOS
        cfg.allowsExpensiveNetworkAccess = true
        session = URLSession(configuration: cfg)
    }

    func enroll(name: String, phone: String?, enrollToken: String?) async throws -> (UUID, Data) {
        struct Res: Decodable { let deviceId: UUID; let secret: String }
        var body: [String: String] = ["name": name]
        if let phone, !phone.isEmpty { body["phone"] = phone }
        let res: Res = try await request("POST", "/api/v1/devices", body: body, bearer: enrollToken)
        return (res.deviceId, try FrameCodec.fromBase64Url(res.secret))
    }

    func postSignals(_ frames: [Data], channel: String, relayDeviceId: String?) async throws -> [FrameResult] {
        struct Res: Decodable { let results: [FrameResult] }
        var body: [String: Any] = ["frames": frames.map(FrameCodec.toBase64Url), "channel": channel]
        if let relayDeviceId { body["relayDeviceId"] = relayDeviceId }
        let res: Res = try await request("POST", "/api/v1/signals", jsonObject: body)
        return res.results
    }

    func alertStatus(_ alertId: UUID) async -> AlertStatus? {
        try? await request("GET", "/api/v1/alerts/\(alertId.uuidString.lowercased())/status")
    }

    private func request<T: Decodable>(_ method: String, _ path: String, body: [String: String]? = nil, bearer: String? = nil) async throws -> T {
        try await request(method, path, jsonObject: body, bearer: bearer)
    }

    private func request<T: Decodable>(_ method: String, _ path: String, jsonObject: [String: Any]?, bearer: String? = nil) async throws -> T {
        guard let url = URL(string: baseUrl() + path) else { throw BackendError(message: "invalid server URL") }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let bearer { req.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        if let jsonObject {
            req.httpBody = try JSONSerialization.data(withJSONObject: jsonObject)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw BackendError(message: "HTTP \(code): \(String(decoding: data.prefix(200), as: UTF8.self))")
        }
        return try JSONDecoder().decode(T.self, from: data)
    }
}
