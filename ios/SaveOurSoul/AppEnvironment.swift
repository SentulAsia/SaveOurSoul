import Foundation
import SOSCore

/// Build-time environment, set per Xcode configuration in project.yml and read from Info.plist.
enum AppEnvironment {
    private static func info(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    /// dev | qa | prod
    static let name = info("SOSEnvironment") ?? "prod"
    static let defaultBackendURL = info("SOSBackendURL") ?? "https://saveoursoul-api.onrender.com"
    static let meshNamespace = info("SOSMeshNamespace").flatMap(MeshNamespace.init(rawValue:)) ?? .prod
    static var isProduction: Bool { name == "prod" }
}
