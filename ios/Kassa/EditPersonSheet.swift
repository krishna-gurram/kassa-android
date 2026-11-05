import SwiftUI
import KassaShared

/// Edit a person (as Android's Edit person sheet): rename them, change what was owed when you added them, or archive them once you are
/// square (Restore for an archived one). Splits and payments stay either way. Every rule is :domain's (`PersonForm`, `EditRules`).
struct EditPersonSheet: View {
    @ObservedObject var model: AppModel
    let personId: Int64
    let onClose: () -> Void

    @State private var loaded: PersonEditData?
    @State private var name = ""
    @State private var theyOwe = true
    @State private var amount = ""
    @State private var startName = ""
    @State private var startTheyOwe = true
    @State private var startAmount = ""
    @State private var problems: [String: String] = [:]
    @State private var submitted = false
    @State private var failure: String?
    @State private var busy = false
    @State private var confirmArchive = false
    @State private var confirmDiscard = false

    private var dirty: Bool {
        return name != startName || theyOwe != startTheyOwe || amount != startAmount
    }

    var body: some View {
        VStack(spacing: 0) {
            if let data = loaded {
                form(data)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .interactiveDismissDisabled(dirty)
        .onAppear { load() }
        .alert(Text("edit.discardTitle"), isPresented: $confirmDiscard) {
            Button("edit.keepEditing", role: .cancel) {}
            Button("edit.discard", role: .destructive) { onClose() }
        } message: {
            Text("edit.discardBody")
        }
    }

    private func load() {
        if loaded != nil { return }
        model.personEditData(id: personId) { data in
            guard let found = data else {
                onClose()
                return
            }
            name = found.name
            theyOwe = found.theyOwe
            amount = found.amount
            startName = found.name
            startTheyOwe = found.theyOwe
            startAmount = found.amount
            loaded = found
        }
    }

    private func refresh() {
        failure = nil
        model.personEditProblems(id: personId, name: name, theyOwe: theyOwe, amount: amount) { found in
            var next: [String: String] = [:]
            for item in found { next[item.field] = item.message }
            problems = next
        }
    }

    private func shown(_ field: String, _ touched: Bool) -> String? {
        if submitted || touched { return problems[field] }
        return nil
    }

    private func leave() {
        if dirty { confirmDiscard = true } else { onClose() }
    }

    private var nameBinding: Binding<String> {
        return Binding<String>(
            get: { name },
            set: { raw in name = FieldBridge.shared.typedName(raw: raw); refresh() }
        )
    }

    private var amountBinding: Binding<String> {
        return Binding<String>(
            get: { amount },
            set: { raw in amount = FieldBridge.shared.typedAmount(raw: raw); refresh() }
        )
    }

    private var directionBinding: Binding<Bool> {
        return Binding<Bool>(get: { theyOwe }, set: { value in theyOwe = value; refresh() })
    }

    private func form(_ data: PersonEditData) -> some View {
        return VStack(spacing: 0) {
            HStack {
                Text("edit.person").font(.headline)
                Spacer()
                Button("account.cancel", action: { leave() }).frame(minHeight: 48)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            ScrollView {
                VStack(spacing: 12) {
                    FormField(label: "person.name", problem: shown("NAME", !name.isEmpty)) {
                        TextField("person.name", text: nameBinding)
                            .textInputAutocapitalization(.words)
                    }
                    Text("edit.alreadyOwedWhenAdded")
                        .font(.caption)
                        .foregroundColor(Palette.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Picker(selection: directionBinding, label: Text("person.direction")) {
                        Text("person.owesMe").tag(true)
                        Text("person.iOwe").tag(false)
                    }
                    .pickerStyle(.segmented)
                    FormField(label: "person.amount", problem: shown("AMOUNT", !amount.isEmpty)) {
                        TextField("person.amountHint", text: amountBinding)
                            .keyboardType(.decimalPad)
                    }
                    Text("edit.personNote")
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let message = failure {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(16)
            }
            VStack(spacing: 8) {
                Button("edit.save") { save() }
                    .buttonStyle(InkButtonStyle(filled: true))
                    .disabled(busy)
                archiveSection(data)
            }
            .padding(16)
        }
    }

    @ViewBuilder
    private func archiveSection(_ data: PersonEditData) -> some View {
        if data.archived {
            Button("edit.restore") { setArchived(false) }
                .buttonStyle(InkButtonStyle(filled: false))
        } else if data.archiveBlocked {
            Text(String(format: String(localized: "edit.personArchiveBlocked"), data.name, data.openSizeText))
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            Button("edit.archive") { confirmArchive = true }
                .buttonStyle(InkButtonStyle(filled: false))
                .alert(Text(String(format: String(localized: "edit.archiveTitle"), data.name)), isPresented: $confirmArchive) {
                    Button("account.cancel", role: .cancel) {}
                    Button("edit.archive") { setArchived(true) }
                } message: {
                    Text(String(format: String(localized: "edit.personArchiveBody"), data.name))
                }
        }
    }

    private func save() {
        submitted = true
        busy = true
        model.personEditProblems(id: personId, name: name, theyOwe: theyOwe, amount: amount) { found in
            var next: [String: String] = [:]
            for item in found { next[item.field] = item.message }
            problems = next
            if !found.isEmpty { busy = false; return }
            model.updatePerson(id: personId, name: name, theyOwe: theyOwe, amount: amount) { outcome in finish(outcome) }
        }
    }

    private func setArchived(_ archived: Bool) {
        busy = true
        model.setPersonArchived(id: personId, archived: archived) { outcome in finish(outcome) }
    }

    private func finish(_ outcome: WriteOutcome) {
        busy = false
        switch outcome {
        case .saved: onClose()
        case .refused(_, let message): failure = message
        case .failed(let message): failure = message
        }
    }
}
