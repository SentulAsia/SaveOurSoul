import SOSCore
import SwiftUI
import UIKit

private let sosRed = Color(red: 0.83, green: 0.18, blue: 0.18)

struct ContentView: View {
    @EnvironmentObject var engine: SosEngine

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if !AppEnvironment.isProduction {
                    Text("\(AppEnvironment.name.uppercased()) BUILD · not for real emergencies")
                        .font(.caption).foregroundStyle(.orange)
                }
                if let e = engine.error {
                    HStack {
                        Text(e).foregroundStyle(sosRed).font(.footnote)
                        Spacer()
                        Button("OK") { engine.error = nil }
                    }
                    .padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                }
                if engine.isActive { ActiveSosView() } else { IdleSosView() }
                ChannelsView()
                if !engine.enrolled { EnrollView() }
                SettingsView()
            }
            .padding()
        }
        .preferredColorScheme(.dark)
        .sheet(item: $engine.smsRequest, onDismiss: { engine.smsSheetDismissed() }) { req in
            if MessageComposer.canSend {
                MessageComposer(request: req) { sent in engine.smsFinished(req, sent: sent) }
                    .ignoresSafeArea()
            } else {
                VStack(spacing: 12) {
                    Text("This device can't send SMS.").font(.headline)
                    Button("OK") { engine.smsFinished(req, sent: false) }
                }
                .padding()
            }
        }
    }
}

private struct IdleSosView: View {
    @EnvironmentObject var engine: SosEngine
    // "Lost" first: being separated from the group is the most common emergency at Hajj.
    @State private var kind: SosKind = .lost
    @State private var countdown: Int?
    @State private var pressing = false

    var body: some View {
        VStack(spacing: 16) {
            Text("Hold the button to call for help").font(.headline)
            Picker("Emergency", selection: $kind) {
                ForEach(SosKind.allCases, id: \.self) { Text(String(describing: $0).capitalized).tag($0) }
            }
            .pickerStyle(.menu)

            ZStack {
                Circle().fill(sosRed).frame(width: 240, height: 240)
                Circle().trim(from: 0, to: pressing ? 1 : 0)
                    .stroke(.white, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: 220, height: 220)
                    .animation(pressing ? .linear(duration: 1.5) : .easeOut(duration: 0.2), value: pressing)
                Text(countdown.map(String.init) ?? "SOS").font(.system(size: 64, weight: .black)).foregroundStyle(.white)
            }
            .onLongPressGesture(minimumDuration: 1.5, maximumDistance: 50) {
                if countdown == nil { countdown = 5 }
            } onPressingChanged: { pressing = $0 }
            .accessibilityLabel("SOS. Hold for one and a half seconds to send an emergency alert.")

            if let c = countdown {
                Text("Sending SOS in \(c) s").bold().foregroundStyle(sosRed)
                Button { countdown = nil } label: {
                    Text("Cancel — I'm OK").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
            }
            if engine.responderStatus == "resolved" {
                Text("Your last alert was resolved by a responder.").font(.footnote)
            }
        }
        .task(id: countdown) {
            guard let c = countdown else { return }
            if c == 0 {
                countdown = nil
                engine.trigger(kind: kind)
                return
            }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            if countdown == c { countdown = c - 1 }
        }
    }
}

private struct ActiveSosView: View {
    @EnvironmentObject var engine: SosEngine
    @State private var confirm = false

    var body: some View {
        VStack(spacing: 12) {
            VStack(spacing: 8) {
                Text("SOS ACTIVE").font(.system(size: 36, weight: .black))
                Text("\(String(describing: engine.kind)) emergency")
                Text(statusText).multilineTextAlignment(.center)
                if let fix = engine.lastFix {
                    Text("Location ±\(Int(fix.horizontalAccuracy)) m shared").font(.caption).opacity(0.8)
                }
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(24)
            .background(sosRed, in: RoundedRectangle(cornerRadius: 20))

            if confirm {
                Button { engine.cancel(); confirm = false } label: {
                    Text("Confirm: I'm safe").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent).tint(.green)
            } else {
                Button { confirm = true } label: {
                    Text("I'm safe — cancel SOS").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
            }
        }
    }

    private var statusText: String {
        switch engine.responderStatus {
        case "acknowledged": return "A responder has acknowledged your SOS. Help is coming."
        case "open": return "Your SOS reached the emergency server. Waiting for a responder…"
        default: return "Sending over internet, SMS and nearby phones…"
        }
    }
}

private struct ChannelsView: View {
    @EnvironmentObject var engine: SosEngine

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Channels").bold()
            row("Internet (Wi-Fi / cellular)", engine.internet)
            row("SMS", engine.sms)
            row("Bluetooth relay (works in crowds, no internet needed)", engine.ble)
            row("Nearby phones (Bluetooth / Wi-Fi)", engine.mesh)
            if engine.pendingFrames > 0 { Text("\(engine.pendingFrames) signal(s) queued for delivery").font(.caption) }
            if engine.relayedForOthers > 0 { Text("Relayed \(engine.relayedForOthers) SOS signal(s) for people nearby").font(.caption) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private func row(_ name: String, _ c: SosEngine.ChannelState) -> some View {
        HStack(alignment: .top) {
            Circle().fill(color(c.health)).frame(width: 10, height: 10).padding(.top, 5)
            VStack(alignment: .leading) {
                Text(name).font(.subheadline)
                if !c.detail.isEmpty { Text(c.detail).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }

    private func color(_ h: SosEngine.Health) -> Color {
        switch h {
        case .ok: return .green
        case .working: return .orange
        case .failed: return sosRed
        case .idle: return .gray
        }
    }
}

private struct EnrollView: View {
    @EnvironmentObject var engine: SosEngine
    @State private var url = SosEngine.shared.settings.backendUrl
    @State private var name = SosEngine.shared.settings.userName
    @State private var phone = ""
    @State private var token = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Register this phone").bold()
            Text("Needs internet once. Afterwards SOS also works over SMS and nearby phones.").font(.caption)
            TextField("SOS server URL", text: $url).textInputAutocapitalization(.never).keyboardType(.URL)
            TextField("Your name", text: $name)
            TextField("Your phone number", text: $phone).keyboardType(.phonePad)
            TextField("Enrollment code (optional)", text: $token).textInputAutocapitalization(.never)
            if let error { Text(error).font(.caption).foregroundStyle(sosRed) }
            Button(busy ? "Registering…" : "Register") {
                busy = true
                error = nil
                Task {
                    defer { busy = false }
                    do {
                        engine.settings.backendUrl = url
                        engine.settings.userName = name
                        let (id, secret) = try await engine.backend.enroll(
                            name: name.isEmpty ? UIDevice.current.name : name, phone: phone, enrollToken: token.isEmpty ? nil : token)
                        try engine.didEnroll(deviceId: id, secret: secret)
                    } catch {
                        self.error = "Registration failed: \(error.localizedDescription)"
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(busy || !url.hasPrefix("http"))
        }
        .textFieldStyle(.roundedBorder)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct SettingsView: View {
    @EnvironmentObject var engine: SosEngine
    @State private var gateway = SosEngine.shared.settings.smsGatewayNumber
    @State private var contacts = SosEngine.shared.settings.emergencyContacts.joined(separator: ", ")
    @State private var delay = String(SosEngine.shared.settings.smsFallbackDelaySec)
    @State private var relay = SosEngine.shared.settings.relayEnabled

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Settings").bold()
            TextField("SMS gateway number", text: $gateway).keyboardType(.phonePad)
                .onChange(of: gateway) { engine.settings.smsGatewayNumber = $0 }
            TextField("Emergency contacts (comma separated)", text: $contacts)
                .onChange(of: contacts) { engine.settings.emergencyContacts = $0.components(separatedBy: ",") }
            TextField("SMS gateway after no internet confirmation (s)", text: $delay).keyboardType(.numberPad)
                .onChange(of: delay) { if let v = Int($0) { engine.settings.smsFallbackDelaySec = v } }
            Toggle("Relay SOS for people nearby while the app is open", isOn: $relay)
                .onChange(of: relay) {
                    engine.settings.relayEnabled = $0
                    engine.updateMeshRunning()
                }
        }
        .textFieldStyle(.roundedBorder)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}
