import AppIntents
import BackgroundTasks
import SOSCore
import SwiftUI

@main
struct SaveOurSoulApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var engine = SosEngine.shared

    init() {
        AppAnalytics.configure()
        // Store-and-forward backstop: iOS wakes us now and then to upload queued frames
        // (our own, or ones relayed for strangers) once the phone is back online.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.flushTask, using: nil) { task in
            let work = Task { @MainActor in
                await SosEngine.shared.flush()
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
            Self.scheduleFlush()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(engine)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .active: engine.onForeground()
            case .background:
                engine.onBackground()
                Self.scheduleFlush()
            default: break
            }
        }
    }

    static let flushTask = "org.saveoursoul.flush"

    static func scheduleFlush() {
        let req = BGAppRefreshTaskRequest(identifier: flushTask)
        req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }
}

/// Lets Siri ("Send SOS with SaveOurSoul"), Shortcuts, Back Tap and the Action button fire an SOS.
struct SendSOSIntent: AppIntent {
    static var title: LocalizedStringResource = "Send SOS"
    static var description = IntentDescription("Immediately sends an SOS over every available channel.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        SosEngine.shared.trigger(kind: .general)
        return .result()
    }
}

struct SOSShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: SendSOSIntent(), phrases: ["Send SOS with \(.applicationName)", "\(.applicationName) emergency"],
                    shortTitle: "Send SOS", systemImageName: "sos")
    }
}
