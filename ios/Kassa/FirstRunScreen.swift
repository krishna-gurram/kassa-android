import SwiftUI
import UniformTypeIdentifiers
import KassaShared

/// Shown when the database is empty: bring a backup (the file picker, and a password when the file has one) or start empty.
/// Failures are the plain sentences of `AppModel`.
struct FirstRunScreen: View {
    @ObservedObject var model: AppModel

    @State private var choosing = false
    @State private var password = ""

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("firstRun.title")
                .font(.largeTitle.weight(.bold))
            Text("firstRun.subtitle")
                .font(.body)
                .foregroundColor(Palette.ink2)
                .multilineTextAlignment(.center)
            Spacer()
            if model.working {
                ProgressView()
            }
            if let message = model.restoreMessage {
                Text(message)
                    .font(.footnote)
                    .foregroundColor(Palette.orange)
                    .multilineTextAlignment(.center)
            }
            Button("firstRun.chooseFile") { choosing = true }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(model.working)
            Button("firstRun.startFresh") { model.startFresh() }
                .buttonStyle(InkButtonStyle(filled: false))
                .disabled(model.working)
        }
        .padding(24)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.ground.ignoresSafeArea())
        .fileImporter(isPresented: $choosing, allowedContentTypes: [KassaTypes.backup], allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let first = urls.first {
                model.chooseBackup(first)
            }
        }
        .alert(Text("password.title"), isPresented: $model.asksPassword) {
            SecureField("password.field", text: $password)
            Button("password.open") {
                let typed = password
                password = ""
                model.submitPassword(typed)
            }
            Button("password.cancel", role: .cancel) {
                password = ""
                model.cancelPassword()
            }
        } message: {
            Text(model.restoreMessage ?? String(localized: "password.message"))
        }
    }
}
