import MessageUI
import SwiftUI

/// SwiftUI wrapper around the system Messages sheet.
struct MessageComposer: UIViewControllerRepresentable {
    let request: SmsRequest
    let onFinish: (Bool) -> Void

    static var canSend: Bool { MFMessageComposeViewController.canSendText() }

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let vc = MFMessageComposeViewController()
        vc.recipients = request.recipients
        vc.body = request.body
        vc.messageComposeDelegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: MFMessageComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let onFinish: (Bool) -> Void
        init(onFinish: @escaping (Bool) -> Void) { self.onFinish = onFinish }

        func messageComposeViewController(_ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult) {
            // The engine clears its sheet binding, which lets SwiftUI dismiss the sheet.
            onFinish(result == .sent)
        }
    }
}
