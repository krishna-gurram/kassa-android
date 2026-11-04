import SwiftUI
import KassaShared

/// Which part of the bill sheet is showing.
enum BillSheetStage: Equatable {
    case form
    case account
    case category
}

/// What is typed on the bill sheet; the rules are :domain's `BillRules` (through `BillInput`).
struct BillFields: Equatable {
    var id: Int64
    var name: String
    var categoryId: Int64
    var accountId: Int64
    var day: String
    var amount: String
    var active: Bool

    init(_ data: BillSheetData) {
        id = data.id
        name = data.name
        categoryId = data.categoryId
        accountId = data.accountId
        day = data.day > 0 ? String(data.day) : ""
        amount = data.amount
        active = data.active
    }

    func input() -> BillInput {
        let dayNumber: Int32 = Int32(day) ?? 0
        return BillInput(id: id, name: name, categoryId: categoryId, accountId: accountId, day: dayNumber, amount: amount, active: active)
    }
}

/// Add or edit a bill: name, category, account, due day, an optional fixed amount, and whether it is on. The checks are :domain's.
struct BillSheet: View {
    @ObservedObject var model: AppModel
    let request: BillSheetRequest

    @State private var fields: BillFields
    @State private var stage: BillSheetStage = .form
    @State private var problems: [String: String] = [:]
    @State private var query = ""
    @State private var submitted = false
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false

    init(model: AppModel, request: BillSheetRequest) {
        self.model = model
        self.request = request
        _fields = State(initialValue: BillFields(request.data))
    }

    private var isNew: Bool { return request.data.id == 0 }

    private var formTitle: String {
        if isNew { return String(localized: "bills.addTitle") }
        return String(localized: "bills.editTitle")
    }

    private func category(_ id: Int64) -> CategoryChoiceData? {
        if id <= 0 { return nil }
        return model.picks?.categoryChoice(id: id)
    }

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .form:
                form
            case .account:
                accountPicker
            case .category:
                CategoryChooser(
                    ledger: model.picks, title: String(localized: "bills.category"), selectedId: fields.categoryId, defaultText: "",
                    onPick: { id in fields.categoryId = id; stage = .form }, onBack: { stage = .form }
                )
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { refresh() }
        .onChange(of: fields) { _ in error = nil; refresh() }
    }

    private func problem(_ field: String) -> String? {
        if !submitted { return nil }
        return problems[field]
    }

    private func filtered(_ text: Binding<String>, _ filter: @escaping (String) -> String) -> Binding<String> {
        return Binding<String>(get: { text.wrappedValue }, set: { raw in text.wrappedValue = filter(raw) })
    }

    private var form: some View {
        VStack(spacing: 0) {
            SheetBar(
                title: formTitle,
                onBack: { model.closeBillSheet() }
            )
            ScrollView {
                VStack(spacing: 12) {
                    FormField(label: "bills.name", problem: problem("NAME")) {
                        TextField("bills.name", text: filtered($fields.name, { FieldBridge.shared.typedName(raw: $0) }))
                    }
                    CategoryChoiceTile(
                        label: String(localized: "bills.category"), choice: category(fields.categoryId), defaultText: "",
                        action: { stage = .category }
                    )
                    problemLine("CATEGORY")
                    AccountChoiceTile(
                        label: "bills.account", choice: model.picks?.accountChoice(id: fields.accountId > 0 ? fields.accountId : -1),
                        action: { query = ""; stage = .account }
                    )
                    problemLine("ACCOUNT")
                    FormField(label: "bills.day", problem: problem("DAY")) {
                        TextField("bills.day", text: filtered($fields.day, { FieldBridge.shared.typedDigits(raw: $0, max: 2) }))
                            .keyboardType(.numberPad)
                    }
                    FormField(label: "bills.amount", problem: problem("AMOUNT")) {
                        TextField("bills.amountHint", text: filtered($fields.amount, { FieldBridge.shared.typedAmount(raw: $0) }))
                            .keyboardType(.decimalPad)
                    }
                    SwitchLine(title: String(localized: "bills.active"), detail: String(localized: "bills.activeDetail"), isOn: $fields.active)
                    if let message = error {
                        Text(message).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if !isNew {
                        Button("bills.delete") { confirmDelete = true }
                            .buttonStyle(InkButtonStyle(filled: false))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: save) { Text("bills.save") }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
        .alert(Text("bills.deleteTitle"), isPresented: $confirmDelete) {
            Button("bills.delete", role: .destructive) { delete() }
            Button("settings.cancel", role: .cancel) {}
        } message: {
            Text("bills.deleteBody")
        }
    }

    @ViewBuilder
    private func problemLine(_ field: String) -> some View {
        if let text = problem(field) {
            Text(text).font(.footnote).foregroundColor(Palette.orange).frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var accountPicker: some View {
        let empty = AccountPickData(filters: [], groups: [], recent: [], noneOpen: true)
        let pick: AccountPickData = model.picks?.payFromPick(query: query) ?? empty
        return VStack(spacing: 8) {
            SheetBar(title: String(localized: "bills.account"), onBack: { stage = .form })
            AccountPickerBody(
                pick: pick, selectedId: fields.accountId, query: $query, filter: .constant("ALL"),
                onPick: { id in fields.accountId = id; stage = .form }
            )
            .padding(.horizontal, 16)
        }
    }

    private func refresh() {
        model.billChecks(fields.input()) { found in
            var next: [String: String] = [:]
            for item in found { next[item.field] = item.message }
            problems = next
        }
    }

    private func save() {
        if busy { return }
        submitted = true
        if !problems.isEmpty { return }
        busy = true
        model.saveBill(fields.input()) { outcome in
            busy = false
            switch outcome {
            case .saved:
                model.closeBillSheet()
            case .refused(_, let message):
                error = message
            case .failed(let message):
                error = message
            }
        }
    }

    private func delete() {
        if busy { return }
        busy = true
        model.deleteBill(id: fields.id) { outcome in
            busy = false
            switch outcome {
            case .saved:
                model.closeBillSheet()
            case .refused(_, let message):
                error = message
            case .failed(let message):
                error = message
            }
        }
    }
}

/// "Enter amount" for a bill notice whose amount is not known (or is being changed): the number pad, then Add.
struct BillAmountSheet: View {
    @ObservedObject var model: AppModel
    let request: BillAmountRequest

    @State private var typed = ""
    @State private var error: String?
    @State private var busy = false

    private var symbol: String { model.picks?.currencySymbol() ?? "" }
    private var cents: Int64 { return EntryBridge.shared.cents(typed: typed) }

    var body: some View {
        VStack(spacing: 12) {
            SheetBar(title: request.notice.name, onBack: { model.closeBillAmountSheet() })
            Spacer(minLength: 0)
            AmountEntry(symbol: symbol, typed: $typed)
                .padding(.horizontal, 16)
            if let message = error {
                Text(message).font(.footnote).foregroundColor(Palette.orange).padding(.horizontal, 16)
            }
            Button(action: add) { Text("bills.addEntry") }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy || cents <= 0)
                .padding(16)
        }
        .background(Palette.ground.ignoresSafeArea())
    }

    private func add() {
        if busy || cents <= 0 { return }
        busy = true
        model.approveBill(request.notice, cents: cents) { outcome in
            busy = false
            switch outcome {
            case .saved:
                model.closeBillAmountSheet()
            case .refused(_, let message):
                error = message
            case .failed(let message):
                error = message
            }
        }
    }
}

/// Settings > Bills: every bill with its day, account and amount, and Add bill.
struct BillsPage: View {
    @ObservedObject var model: AppModel

    var body: some View {
        ScrollView {
            BillsContent(
                rows: model.billsPage?.rows ?? [],
                onOpen: { id in model.openBill(id: id) },
                onAdd: { model.openBill(id: 0) }
            )
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// The bills list without its scroll view, so a screenshot can draw it.
struct BillsContent: View {
    let rows: [BillRowData]
    let onOpen: (Int64) -> Void
    let onAdd: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("bills.title")
                .font(.largeTitle.weight(.bold))
                .padding(.top, 16)
                .padding(.bottom, 8)
            if rows.isEmpty {
                Text("bills.empty")
                    .font(.body)
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 12)
            }
            ForEach(rows, id: \.id) { row in
                Button(action: { onOpen(row.id) }) { line(row) }
                    .buttonStyle(.plain)
                Hairline()
            }
            Button(action: onAdd) { Text("bills.add") }
                .buttonStyle(InkButtonStyle(filled: true))
                .padding(.top, 20)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 96)
    }

    private func line(_ row: BillRowData) -> some View {
        return HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name)
                    .font(.body.weight(.semibold))
                    .foregroundColor(row.active ? Palette.ink : Palette.ink2)
                    .lineLimit(1)
                Text(detail(row))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(row.amountText)
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .foregroundColor(row.active ? Palette.ink : Palette.ink2)
                .lineLimit(1)
        }
        .padding(.vertical, 6)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
    }

    private func detail(_ row: BillRowData) -> String {
        var parts: [String] = [String(format: String(localized: "bills.dayOf"), Int(row.day))]
        if !row.accountName.isEmpty { parts.append(row.accountName) }
        if !row.active { parts.append(String(localized: "bills.off")) }
        return parts.joined(separator: " \u{00B7} ")
    }
}
