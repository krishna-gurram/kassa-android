import SwiftUI
import KassaShared

/// Which part of a payment sheet is showing: the form, the account picker or the calendar.
enum SheetStage {
    case form
    case pickAccount
    case pickDate
}

/// Today, Yesterday, Other day: the first two set the day; the third opens the calendar and shows the day picked.
struct DayChips: View {
    let epochDay: Int
    let today: Int
    let onChoose: (Int) -> Void
    let onOther: () -> Void

    private var isToday: Bool { epochDay == today }
    private var isYesterday: Bool { epochDay == today - 1 }
    private var otherLabel: String {
        if isToday || isYesterday { return String(localized: "entry.otherDay") }
        return KassaDates.shortDate(epochDay: epochDay)
    }

    var body: some View {
        HStack(spacing: 8) {
            FilterChip(title: String(localized: "entry.today"), selected: isToday, action: { onChoose(today) })
            FilterChip(title: String(localized: "entry.yesterday"), selected: isYesterday, action: { onChoose(today - 1) })
            FilterChip(title: otherLabel, selected: !isToday && !isYesterday, action: onOther)
            Spacer(minLength: 0)
        }
    }
}

/// The graphical calendar of "Other day", with a way back to the form.
struct DayPickerStage: View {
    @Binding var epochDay: Int
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            SheetBar(title: String(localized: "entry.date"), onBack: onDone)
            DatePicker(selection: dateBinding, displayedComponents: .date) {
                Text("entry.date")
            }
            .datePickerStyle(.graphical)
            .environment(\.timeZone, KassaDates.utc)
            .environment(\.calendar, KassaDates.gregorian)
            .padding(.horizontal, 16)
            Spacer(minLength: 0)
        }
    }

    private var dateBinding: Binding<Date> {
        return Binding<Date>(
            get: { KassaDates.date(epochDay: epochDay) },
            set: { epochDay = KassaDates.epochDay(of: $0) }
        )
    }
}

/// The typed amount with the ledger currency's bare symbol beside it, over the number pad. Typed amounts are never masked.
struct AmountEntry: View {
    let symbol: String
    @Binding var typed: String

    private var decimalMark: String { String(EntryBridge.shared.display(typed: "0.").dropFirst()) }

    var body: some View {
        VStack(spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(symbol)
                    .font(.title2.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                Text(EntryBridge.shared.display(typed: typed))
                    .font(.system(size: 40, weight: .bold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .foregroundColor(typed.isEmpty ? Palette.ink2 : Palette.ink)
            }
            .frame(maxWidth: .infinity, minHeight: 52)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("entry.amount"))
            .accessibilityValue(Text(symbol + EntryBridge.shared.display(typed: typed)))
            NumberPad(decimalMark: decimalMark, onKey: { key in typed = EntryBridge.shared.press(typed: typed, key: key) })
        }
    }
}

/// A tile showing the account chosen (or "Choose"), opening the picker.
struct AccountChoiceTile: View {
    let label: LocalizedStringKey
    let choice: AccountChoiceData?
    let action: () -> Void

    var body: some View {
        EntryTile(label: label, value: choice?.name ?? "", selected: false, action: action) {
            icon
        }
    }

    @ViewBuilder
    private var icon: some View {
        if let chosen = choice {
            AccountIconView(type: chosen.type, size: 32)
        } else {
            EmptyTileIcon()
        }
    }
}

private func nowMillis() -> Int64 {
    return Int64(Date().timeIntervalSince1970 * 1000)
}

// MARK: - Pay card

/// Pays a card: balance payable, the total balance or another amount (the first of what is owed is picked; nothing else is), the
/// account it is paid from (only the card's own "pay from", when set and open, is preselected), and the date. It saves a transfer from
/// that account to the card, as Android does. The words under each option are `LedgerView.cardPayData`'s.
struct PayCardSheet: View {
    @ObservedObject var model: AppModel
    let request: PaySheetRequest

    @State private var choice: String
    @State private var typed = ""
    @State private var payFromId: Int64
    @State private var epochDay: Int
    @State private var stage: SheetStage = .form
    @State private var query = ""
    @State private var error: String?
    @State private var busy = false

    init(model: AppModel, request: PaySheetRequest) {
        self.model = model
        self.request = request
        _choice = State(initialValue: request.data.defaultChoice)
        _payFromId = State(initialValue: request.data.defaultPayFromId)
        _epochDay = State(initialValue: model.today)
    }

    private var symbol: String { model.picks?.currencySymbol() ?? "" }

    private var chosenOption: CardPayOptionData? {
        for option in request.data.options where option.choice == choice { return option }
        return nil
    }

    private var cents: Int64 {
        if choice == "OTHER" { return EntryBridge.shared.cents(typed: typed) }
        return chosenOption?.cents ?? 0
    }

    private var amountLabel: String {
        if choice == "OTHER" { return symbol + EntryBridge.shared.display(typed: typed) }
        return chosenOption?.amountText ?? ""
    }

    private var buttonTitle: String {
        if cents > 0 { return String(format: String(localized: "payCard.record"), amountLabel) }
        return String(localized: "payCard.enterAmount")
    }

    private var payFromChoice: AccountChoiceData? {
        if payFromId <= 0 { return nil }
        return model.picks?.accountChoice(id: payFromId)
    }

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .form:
                form
            case .pickAccount:
                accountPicker
            case .pickDate:
                DayPickerStage(epochDay: $epochDay, onDone: { stage = .form })
            }
        }
        .background(Palette.ground.ignoresSafeArea())
    }

    private var form: some View {
        VStack(spacing: 0) {
            SheetBar(title: String(format: String(localized: "payCard.title"), request.data.name), onBack: { model.closePaySheet() })
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(request.data.options, id: \.choice) { option in
                        optionRow(option)
                    }
                    if choice == "OTHER" {
                        AmountEntry(symbol: symbol, typed: $typed)
                    }
                    AccountChoiceTile(label: "payCard.payFrom", choice: payFromChoice, action: { query = ""; stage = .pickAccount })
                    DayChips(epochDay: epochDay, today: model.today, onChoose: { day in epochDay = day }, onOther: { stage = .pickDate })
                    if let problem = error {
                        Text(problem)
                            .font(.footnote)
                            .foregroundColor(Palette.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: save) { Text(buttonTitle) }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
    }

    private func optionRow(_ option: CardPayOptionData) -> some View {
        let selected = option.choice == choice
        return Button(action: { choice = option.choice; error = nil }) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Wording.payOptionTitle(option))
                    .font(.body.weight(.semibold))
                    .foregroundColor(Palette.ink)
                Text(Wording.payOptionDetail(option))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? Palette.ink : Palette.line, lineWidth: selected ? 2 : 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : [.isButton])
    }

    private var accountPicker: some View {
        let empty = AccountPickData(filters: [], groups: [], recent: [], noneOpen: true)
        let pick: AccountPickData = model.picks?.payFromPick(query: query) ?? empty
        return VStack(spacing: 8) {
            SheetBar(title: String(localized: "payCard.payFrom"), onBack: { stage = .form })
            AccountPickerBody(
                pick: pick, selectedId: payFromId, query: $query, filter: .constant("ALL"),
                onPick: { id in payFromId = id; error = nil; stage = .form }
            )
            .padding(.horizontal, 16)
        }
    }

    private func save() {
        if busy { return }
        busy = true
        error = nil
        let moment = EntryBridge.shared.savedMillis(
            epochDay: Int32(epochDay), zoneId: TimeZone.current.identifier, nowMillis: nowMillis(), originalMillis: 0
        )
        model.payCard(cardId: request.data.cardId, payFromId: payFromId, cents: cents, millis: moment) { outcome in
            busy = false
            switch outcome {
            case .saved:
                model.closePaySheet()
            case .refused(_, let message):
                error = message
            case .failed(let message):
                error = message
            }
        }
    }
}

// MARK: - Person payment

/// Money between you and one person, either way: they paid me or I paid or lent them, the amount (it starts on settling the balance),
/// the account it went through (nothing is guessed: you pick it), and the date. An edited payment opens filled in with Delete.
/// Saves a PERSON_IN or PERSON_OUT entry; the amount and sides carry no sign, as on Android.
struct PersonPaymentSheet: View {
    @ObservedObject var model: AppModel
    let request: PersonSheetRequest

    @State private var kind: String
    @State private var typed: String
    @State private var accountId: Int64
    @State private var epochDay: Int
    @State private var stage: SheetStage = .form
    @State private var query = ""
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false

    init(model: AppModel, request: PersonSheetRequest) {
        self.model = model
        self.request = request
        if let edit = request.editing {
            _kind = State(initialValue: edit.kind)
            _typed = State(initialValue: EntryBridge.shared.typedOf(cents: edit.amount))
            _accountId = State(initialValue: edit.accountId)
            let day = EntryBridge.shared.dayOf(epochMillis: edit.epochMillis, zoneId: TimeZone.current.identifier)
            _epochDay = State(initialValue: Int(day))
        } else {
            _kind = State(initialValue: request.defaultKind)
            _typed = State(initialValue: request.suggestedCents > 0 ? EntryBridge.shared.typedOf(cents: request.suggestedCents) : "")
            _accountId = State(initialValue: 0)
            _epochDay = State(initialValue: model.today)
        }
    }

    private var symbol: String { model.picks?.currencySymbol() ?? "" }

    private var isEditing: Bool { request.editing != nil }

    private var accountChoice: AccountChoiceData? {
        if accountId <= 0 { return nil }
        return model.picks?.accountChoice(id: accountId)
    }

    private var title: LocalizedStringKey {
        return isEditing ? "personPayment.editTitle" : "personPayment.title"
    }

    var body: some View {
        VStack(spacing: 0) {
            switch stage {
            case .form:
                form
            case .pickAccount:
                accountPicker
            case .pickDate:
                DayPickerStage(epochDay: $epochDay, onDone: { stage = .form })
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .alert(Text("personPayment.deleteTitle"), isPresented: $confirmDelete) {
            Button("personPayment.keep", role: .cancel) {}
            Button("personPayment.deleteConfirm", role: .destructive) { delete() }
        } message: {
            Text(String(format: String(localized: "personPayment.deleteBody"), request.amountText, request.personName))
        }
    }

    private var form: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button(action: { model.closePersonSheet() }) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel(Text("a11y.back"))
                Text(title).font(.headline).lineLimit(1)
                Spacer()
                if isEditing {
                    Button("personPayment.delete") { confirmDelete = true }
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.orange)
                        .frame(minHeight: 44)
                        .padding(.trailing, 8)
                }
            }
            .padding(.horizontal, 8)
            ScrollView {
                VStack(spacing: 10) {
                    HStack(spacing: 8) {
                        FilterChip(
                            title: String(format: String(localized: "personPayment.paidMe"), request.personName),
                            selected: kind == "PERSON_IN", action: { kind = "PERSON_IN"; error = nil }
                        )
                        FilterChip(
                            title: String(format: String(localized: "personPayment.iPaid"), request.personName),
                            selected: kind == "PERSON_OUT", action: { kind = "PERSON_OUT"; error = nil }
                        )
                        Spacer(minLength: 0)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    AmountEntry(symbol: symbol, typed: $typed)
                    AccountChoiceTile(
                        label: kind == "PERSON_IN" ? "personPayment.into" : "personPayment.from", choice: accountChoice,
                        action: { query = ""; stage = .pickAccount }
                    )
                    DayChips(epochDay: epochDay, today: model.today, onChoose: { day in epochDay = day }, onOther: { stage = .pickDate })
                    if let problem = error {
                        Text(problem)
                            .font(.footnote)
                            .foregroundColor(Palette.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: save) { Text(isEditing ? LocalizedStringKey("personPayment.saveChanges") : LocalizedStringKey("personPayment.save")) }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
    }

    private var accountPicker: some View {
        let empty = AccountPickData(filters: [], groups: [], recent: [], noneOpen: true)
        let keep: Int64 = request.editing?.accountId ?? 0
        let pick: AccountPickData = model.picks?.personPaymentPick(query: query, keepAccountId: keep) ?? empty
        return VStack(spacing: 8) {
            SheetBar(title: String(localized: "personPayment.account"), onBack: { stage = .form })
            AccountPickerBody(
                pick: pick, selectedId: accountId, query: $query, filter: .constant("ALL"),
                onPick: { id in accountId = id; error = nil; stage = .form }
            )
            .padding(.horizontal, 16)
        }
    }

    private func input() -> PersonPaymentInput {
        let original: Int64 = request.editing?.epochMillis ?? 0
        let moment = EntryBridge.shared.savedMillis(
            epochDay: Int32(epochDay), zoneId: TimeZone.current.identifier, nowMillis: nowMillis(), originalMillis: original
        )
        let note = PersonPaymentBridge.shared.note(
            kind: kind, personName: request.personName, editingKind: request.editing?.kind ?? "", editingNote: request.editing?.note ?? ""
        )
        return PersonPaymentInput(
            id: request.editing?.id ?? 0, personId: request.personId, kind: kind, amount: EntryBridge.shared.cents(typed: typed),
            accountId: accountId, epochMillis: moment, note: note
        )
    }

    private func finished(_ outcome: WriteOutcome) {
        busy = false
        switch outcome {
        case .saved:
            model.closePersonSheet()
        case .refused(_, let message):
            error = message
        case .failed(let message):
            error = message
        }
    }

    private func save() {
        if busy { return }
        busy = true
        error = nil
        let made = input()
        if isEditing {
            model.updatePersonPayment(made, done: { outcome in finished(outcome) })
        } else {
            model.addPersonPayment(made, done: { outcome in finished(outcome) })
        }
    }

    private func delete() {
        guard let edit = request.editing, !busy else { return }
        busy = true
        model.deletePersonPayment(id: edit.id, done: { outcome in finished(outcome) })
    }
}
