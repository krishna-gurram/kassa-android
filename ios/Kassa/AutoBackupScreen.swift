import SwiftUI
import UniformTypeIdentifiers
import KassaShared

/// Settings > Automatic backups: choose a folder, add a password, see when the last backup was made and whether it worked. Kassa backs up
/// when you open it and the last good backup is more than a day old, and asks iOS for a background refresh as a bonus. iOS cannot promise
/// a daily run, and the page says so. The rules are :domain's; this lays them out.
struct AutoBackupPage: View {
    @ObservedObject var model: AppModel

    @State private var choosingFolder = false
    @State private var askingNote = false
    @State private var askingPassword = false
    @State private var password = ""
    @State private var confirmingStop = false

    var body: some View {
        let info: AutoBackupInfo = model.autoBackupInfo
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("autoBackup.title")
                    .font(.largeTitle.weight(.bold))
                    .padding(.top, 16)
                Text("autoBackup.intro")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                noteBlock(info)
                Hairline()
                folderRow(info)
                Hairline()
                passwordRow(info)
                Hairline()
                statusBlock(info)
                actions(info)
                Text("autoBackup.limits")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .padding(.top, 8)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 96)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { model.refreshAutoBackupInfo() }
        .fileImporter(isPresented: $choosingFolder, allowedContentTypes: [UTType.folder], allowsMultipleSelection: false) { result in
            model.chooseBackupFolder(result)
        }
        .alert(Text("autoBackup.note"), isPresented: $askingNote) {
            Button("autoBackup.noteContinue") {
                model.dismissAutoBackupNote()
                choosingFolder = true
            }
            Button("settings.cancel", role: .cancel) {}
        }
        .alert(Text("autoBackup.passwordTitle"), isPresented: $askingPassword) {
            SecureField("settings.backup.passwordField", text: $password)
            Button("settings.backup.save") {
                let typed = password
                password = ""
                model.setAutoBackupPassword(typed)
            }
            Button("settings.cancel", role: .cancel) { password = "" }
        } message: {
            Text("autoBackup.passwordMessage")
        }
        .alert(Text("autoBackup.stopTitle"), isPresented: $confirmingStop) {
            Button("autoBackup.stop", role: .destructive) { model.stopAutoBackup() }
            Button("settings.cancel", role: .cancel) {}
        } message: {
            Text("autoBackup.stopMessage")
        }
    }

    // MARK: Pieces

    /// The one-time note, on the page until the person dismisses it.
    @ViewBuilder
    private func noteBlock(_ info: AutoBackupInfo) -> some View {
        if !info.noteDismissed {
            VStack(alignment: .leading, spacing: 4) {
                Text("autoBackup.note")
                    .font(.footnote)
                    .foregroundColor(Palette.ink)
                Button(action: { model.dismissAutoBackupNote() }) {
                    Text("autoBackup.noteDismiss")
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minWidth: 48, minHeight: 48, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.top, 4)
        }
    }

    private func folderRow(_ info: AutoBackupInfo) -> some View {
        return Button(action: { pickFolder(info) }) {
            row(title: "autoBackup.folder", detail: info.folderName ?? String(localized: "autoBackup.noFolder"))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("autoBackupFolder")
    }

    private func passwordRow(_ info: AutoBackupInfo) -> some View {
        let detail: String = info.hasPassword ? String(localized: "autoBackup.passwordOn") : String(localized: "autoBackup.passwordOff")
        return Button(action: { askingPassword = true }) {
            row(title: "autoBackup.password", detail: detail)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("autoBackupPassword")
    }

    private func row(title: LocalizedStringKey, detail: String) -> some View {
        return HStack(spacing: 8) {
            Text(title)
                .font(.body)
                .foregroundColor(Palette.ink)
            Spacer(minLength: 8)
            Text(detail)
                .font(.body)
                .foregroundColor(Palette.ink2)
                .lineLimit(1)
                .truncationMode(.middle)
            Image(systemName: "chevron.right")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
        }
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private func statusBlock(_ info: AutoBackupInfo) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if model.autoBackupRunning {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("settings.backup.working")
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                }
            }
            Text(lastText(info))
                .font(.body)
                .foregroundColor(Palette.ink)
            if let problem = info.message {
                Text(problem)
                    .font(.footnote)
                    .foregroundColor(Palette.orange)
            }
        }
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private func actions(_ info: AutoBackupInfo) -> some View {
        if info.folderName != nil {
            Hairline()
            Button(action: { model.backUpToFolderNow() }) {
                Text("autoBackup.now")
                    .font(.body)
                    .foregroundColor(Palette.ink)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(model.autoBackupRunning)
            .accessibilityIdentifier("autoBackupNow")
            Hairline()
            Button(action: { confirmingStop = true }) {
                Text("autoBackup.stop")
                    .font(.body)
                    .foregroundColor(Palette.orange)
                    .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: Words

    private func lastText(_ info: AutoBackupInfo) -> String {
        guard let when = info.lastSuccess else { return String(localized: "autoBackup.never") }
        let formatter = DateFormatter()
        formatter.locale = Locale.current
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return String(format: String(localized: "autoBackup.last"), formatter.string(from: when))
    }

    /// The note comes first, once; then the picker.
    private func pickFolder(_ info: AutoBackupInfo) {
        if info.noteDismissed {
            choosingFolder = true
        } else {
            askingNote = true
        }
    }
}
