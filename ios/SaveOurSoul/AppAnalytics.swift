import FirebaseAnalytics
import FirebaseCore
import Foundation

/// Anonymous, opt-in usage analytics (Firebase, one project per environment). Same rules and
/// event names as Android's Analytics.kt (docs/ANALYTICS.md):
///
///  - Off until the user explicitly agrees; collection is disabled in Info.plist by default.
///  - Never on the SOS path: every call is a no-op when Firebase is not configured (no
///    GoogleService-Info.plist in this build) or the user has not consented.
///  - Never personal data: no location, device/alert/frame ids, names, phone numbers or contacts.
///  - No advertising: ad storage, ad user data and ad personalisation are always denied.
@MainActor
enum AppAnalytics {
    private static let consentKey = "analytics_consent"

    /// False when this build has no Firebase config: then the consent prompt is not shown either.
    private(set) static var available = false

    /// Call once at launch.
    static func configure() {
        guard FirebaseApp.app() == nil,
              Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return }
        FirebaseApp.configure()
        available = true
        applyConsent()
    }

    /// nil = not asked yet.
    static var consent: Bool? {
        get { UserDefaults.standard.object(forKey: consentKey) as? Bool }
        set {
            if let newValue { UserDefaults.standard.set(newValue, forKey: consentKey) } else { UserDefaults.standard.removeObject(forKey: consentKey) }
            applyConsent()
        }
    }

    static func applyConsent() {
        guard available else { return }
        let granted = consent == true
        Analytics.setConsent([
            .analyticsStorage: granted ? .granted : .denied,
            .adStorage: .denied,
            .adUserData: .denied,
            .adPersonalization: .denied,
        ])
        Analytics.setAnalyticsCollectionEnabled(granted)
        if !granted { Analytics.resetAnalyticsData() }
    }

    /// Values must be String, Int or Double, never personal data.
    static func event(_ name: String, _ params: [String: Any] = [:]) {
        guard available, consent == true else { return }
        Analytics.logEvent(name, parameters: params.mapValues { v -> Any in
            if let b = v as? Bool { return b ? "yes" : "no" }
            return v
        })
    }

    /// Coarse duration buckets: precise timings could help re-identify a person.
    static func timeBucket(_ seconds: TimeInterval) -> String {
        switch seconds {
        case ..<10: return "<10s"
        case ..<30: return "10-30s"
        case ..<120: return "30s-2m"
        case ..<600: return "2-10m"
        case ..<3600: return "10-60m"
        default: return ">1h"
        }
    }
}
