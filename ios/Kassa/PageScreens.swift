import SwiftUI
import KassaShared

/// The top of a page: a back arrow at the left, the title centred, and the eye (Hide balances) and "Edit" at the right.
struct PageTop: View {
    let title: String
    let hidden: Bool
    let onToggle: () -> Void
    let onBack: () -> Void
    /// "Edit" of an account, card or person page; nil leaves it out.
    var onEdit: (() -> Void)? = nil

    var body: some View {
        ZStack {
            Text(title)
                .font(Font.kassa(19, .bold, relativeTo: .headline))
                .foregroundColor(Palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, onEdit == nil ? 56 : 104)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 4) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minWidth: 48, minHeight: 48)
                }
                .accessibilityLabel(Text("a11y.back"))
                Spacer(minLength: 8)
                editButton
                EyeButton(hidden: hidden, onToggle: onToggle)
            }
        }
        .padding(.top, 4)
    }

    @ViewBuilder
    private var editButton: some View {
        if let edit = onEdit {
            Button(action: edit) {
                Text("edit.button")
                    .font(Font.kassa(16, .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 48, minHeight: 48)
            }
        }
    }
}

/// A figure with a small label over it, in a quiet box (the two boxes under a page's big figure).
struct FigureBox: View {
    let label: LocalizedStringKey
    let value: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundColor(Palette.ink2)
            Text(value)
                .font(.title3.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .foregroundColor(color)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Palette.ground.opacity(0.55)))
        .accessibilityElement(children: .combine)
    }
}

/// The big figure of a page under its small label, in a tinted block.
struct PageHero<Content: View>: View {
    let tint: Color
    let content: Content

    init(tint: Color, @ViewBuilder content: () -> Content) {
        self.tint = tint
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(tint))
    }
}

/// The small uppercase label over a figure inside a hero.
struct HeroCaption: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .textCase(.uppercase)
            .foregroundColor(Palette.ink2)
            .lineLimit(1)
    }
}

/// The big figure of a hero.
struct HeroFigure: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.largeTitle.weight(.semibold))
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .foregroundColor(color)
    }
}

/// How the heroes of the card, loan, account and person pages colour their figures: the hero's own ink (brown on orange, green on
/// green), plain ink on grey, and plain ink while amounts are hidden. The tone itself always comes from :domain.
enum PageStyle {
    static func figureColor(_ tone: Tone, hidden: Bool) -> Color {
        if hidden || tone.name == "Ink" { return Palette.ink }
        return Palette.heroInk(tone)
    }

    static func centsColor(_ tone: Tone, hidden: Bool) -> Color {
        if hidden { return Palette.ink }
        if tone.name == "Ink" { return Palette.ink3 }
        return Palette.heroInk(tone).opacity(0.7)
    }
}

/// The big figure of a page's hero, with lighter cents.
struct HeroAmount: View {
    let text: String
    let tone: Tone
    let hidden: Bool
    /// A grey hero whatever the tone (the loan page): plain ink with lighter cents.
    var grey: Bool = false

    var body: some View {
        AmountText(
            text: text, hidden: hidden, size: 52,
            color: grey ? Palette.ink : PageStyle.figureColor(tone, hidden: hidden),
            centsColor: grey ? (hidden ? Palette.ink : Palette.ink3) : PageStyle.centsColor(tone, hidden: hidden)
        )
        .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
        .padding(.top, 4)
    }
}

/// A small uppercase label over a figure along the foot of a hero.
struct HeroFootItem: View {
    let label: LocalizedStringKey
    let value: String
    let tone: Tone
    let hidden: Bool
    var color: Color = Palette.ink

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .kassa(.label)
                .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroInk(tone))
                .lineLimit(1)
            Text(value)
                .font(Font.kassa(20, .semibold, relativeTo: .title3).monospacedDigit())
                .foregroundColor(color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .combine)
    }
}

/// One pill of a page's action row: ink when it is the primary action, a quiet chip otherwise. 56 pt tall.
struct PagePill: View {
    let title: LocalizedStringKey
    let filled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, filled ? 8 : 24)
        }
        .buttonStyle(InkButtonStyle(filled: filled))
    }
}

/// Close account / Reopen under a page's hero. Only a settled account closes: a balance left shows its sentence ("Pay off or move the
/// balance first.") where the button is; closing asks first. Nothing is deleted.
struct ClosingSection: View {
    let name: String
    /// Epoch day the account was closed, 0 while open.
    let closedDay: Int
    /// Why it can't close yet, "" when it can.
    let closeProblem: String
    let onSetClosed: (Bool, @escaping (WriteOutcome) -> Void) -> Void

    @State private var confirm = false
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if closedDay > 0 {
                Text(String(format: String(localized: "accountPage.closedOn"), KassaDates.shortDateYear(epochDay: closedDay)))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                Button(action: { run(false) }) {
                    Text("accountPage.reopen")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minHeight: 48)
                }
            } else {
                Button(action: tapClose) {
                    Text("accountPage.close")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.ink2)
                        .frame(minHeight: 48)
                }
            }
            if let text = message {
                Text(text)
                    .font(.footnote)
                    .foregroundColor(Palette.orange)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .alert(Text(String(format: String(localized: "accountPage.closeTitle"), name)), isPresented: $confirm) {
            Button("accountPage.keepOpen", role: .cancel) {}
            Button("accountPage.close") { run(true) }
        } message: {
            Text("accountPage.closeBody")
        }
    }

    private func tapClose() {
        if closeProblem.isEmpty {
            message = nil
            confirm = true
        } else {
            message = closeProblem
        }
    }

    private func run(_ closed: Bool) {
        onSetClosed(closed) { outcome in
            switch outcome {
            case .saved:
                message = nil
            case .refused(_, let text):
                message = text
            case .failed(let text):
                message = text
            }
        }
    }
}

// MARK: - Account

/// A plain account's page (bank, cash, prepaid, investment, loan, other, and a card without statement days): the balance at the end of
/// the month shown with money in and out along the foot of a hero, Add entry and Correct, Close or Reopen, and the month's entries by
/// day with Activity's rows. A loan without terms shows this with "Add terms" (a loan with terms has `LoanPageContent`); an investment
/// shows only this for now. Without its scroll view, so a screenshot can draw it.
struct AccountPageContent: View {
    let data: AccountPageData
    let hidden: Bool
    let today: Int
    let onToggleHidden: () -> Void
    let onBack: () -> Void
    /// -1 is a month back, +1 a month forward.
    let onShift: (Int) -> Void
    let onSetClosed: (Bool, @escaping (WriteOutcome) -> Void) -> Void
    var onOpen: ((EntryRowData) -> Void)? = nil
    /// "Add terms" of a loan that has none, and "Correct balance" (any account; a card's own page has no button yet).
    var onAddTerms: (() -> Void)? = nil
    var onCorrect: (() -> Void)? = nil
    /// "Edit": rename, details, balance, kind (batch Q).
    var onEdit: (() -> Void)? = nil
    /// "Add entry": a new entry that starts on this account.
    var onAddEntry: (() -> Void)? = nil

    private var title: String {
        if data.last4.isEmpty { return data.name }
        return data.name + " \u{2022}\u{2022}" + data.last4
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: title, hidden: hidden, onToggle: onToggleHidden, onBack: onBack, onEdit: onEdit)
            MonthSwitcher(
                label: KassaDates.monthYear(year: Int(data.year), month: Int(data.month)),
                canForward: !data.isCurrentMonth, canBack: data.hasEarlier, onShift: onShift
            )
            hero
            actions
            ClosingSection(name: data.name, closedDay: Int(data.closedDay), closeProblem: data.closeProblem, onSetClosed: onSetClosed)
            entries
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private var hero: some View {
        TonalHero(tone: data.balanceTone, hidden: hidden) {
            HeroLabel(text: LocalizedStringKey(Wording.accountBalanceTitle(data)), tone: data.balanceTone, hidden: hidden)
            HeroAmount(text: data.balanceText, tone: data.balanceTone, hidden: hidden)
            HStack(alignment: .top, spacing: 36) {
                HeroFootItem(
                    label: "accountPage.in", value: data.inText, tone: data.balanceTone, hidden: hidden,
                    color: hidden ? Palette.ink : Palette.green
                )
                HeroFootItem(label: "accountPage.out", value: data.outText, tone: data.balanceTone, hidden: hidden)
                Spacer(minLength: 0)
            }
            .padding(.top, 14)
            notes
        }
    }

    @ViewBuilder
    private var notes: some View {
        if data.type == "CREDIT_CARD" && data.closedDay == 0 && !data.hasCardPage {
            Text("accountPage.cardNeedsDays")
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroInk(data.balanceTone))
                .padding(.top, 10)
        }
        if data.maturityDay > 0 {
            Text(String(format: String(localized: "accounts.maturesOn"), KassaDates.shortDateYear(epochDay: Int(data.maturityDay))))
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroInk(data.balanceTone))
                .padding(.top, 10)
        }
    }

    private var noTerms: Bool {
        return data.type == "LOAN" && onAddTerms != nil
    }

    private var hasPrimary: Bool {
        if noTerms { return true }
        return data.closedDay == 0 && onAddEntry != nil
    }

    @ViewBuilder
    private var actions: some View {
        if noTerms {
            Text("loan.noTerms")
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        HStack(spacing: 12) {
            primaryPill
            correctPill
        }
    }

    @ViewBuilder
    private var primaryPill: some View {
        if noTerms, let addTerms = onAddTerms {
            PagePill(title: "loan.addTerms", filled: true, action: addTerms)
        } else if data.closedDay == 0, let add = onAddEntry {
            PagePill(title: "d6.addEntry", filled: true, action: add)
        }
    }

    @ViewBuilder
    private var correctPill: some View {
        if let correct = onCorrect {
            PagePill(title: "d6.correct", filled: false, action: correct)
                .fixedSize(horizontal: hasPrimary, vertical: false)
                .accessibilityLabel(Text("correction.open"))
        }
    }

    @ViewBuilder
    private var entries: some View {
        if data.entryCount == 0 {
            Text("accountPage.nothingThisMonth")
                .font(Font.kassa(15, .regular, relativeTo: .body))
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 24)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(data.days, id: \.epochDay) { day in
                    DaySection(day: day, today: today, onOpen: onOpen)
                }
            }
        }
    }
}

// MARK: - Card

/// The statement navigator: a title with the stretch it covers under it, and an arrow at each edge.
struct StatementSwitcher: View {
    let title: String
    let subtitle: String
    let canBack: Bool
    let canForward: Bool
    let onShift: (Int) -> Void

    var body: some View {
        HStack {
            arrow("chevron.left", "a11y.olderStatement", enabled: canBack, by: -1)
            Spacer(minLength: 8)
            VStack(spacing: 2) {
                Text(title)
                    .font(Font.kassa(17, .semibold, relativeTo: .headline))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(subtitle)
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            Spacer(minLength: 8)
            arrow("chevron.right", "a11y.newerStatement", enabled: canForward, by: 1)
        }
    }

    private func arrow(_ symbol: String, _ description: LocalizedStringKey, enabled: Bool, by direction: Int) -> some View {
        Button(action: { onShift(direction) }) {
            Image(systemName: symbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(enabled ? Palette.ink : Palette.ink2.opacity(0.4))
                .frame(minWidth: 48, minHeight: 48)
        }
        .disabled(!enabled)
        .accessibilityLabel(Text(description))
    }
}

/// A card with statement days: an orange hero with Balance payable (what is due, and when) and Outstanding and Total balance along its
/// foot, Pay card beside Correct, then statement by statement the card's entries with Activity's rows. The figures, words and
/// colours come from `LedgerView.cardPage`. Without its scroll view, so a screenshot can draw it.
struct CardPageContent: View {
    let data: CardPageData
    let hidden: Bool
    let today: Int
    let onToggleHidden: () -> Void
    let onBack: () -> Void
    let onPay: () -> Void
    /// -1 is a statement back, +1 forward.
    let onShift: (Int) -> Void
    let onSetClosed: (Bool, @escaping (WriteOutcome) -> Void) -> Void
    var onOpen: ((EntryRowData) -> Void)? = nil
    /// "Correct balance": the card shows what is owed, typed as what the issuer shows.
    var onCorrect: (() -> Void)? = nil
    /// "Edit": name, statement day, due day, limit, pay from, balance (batch Q).
    var onEdit: (() -> Void)? = nil

    private var title: String {
        if data.last4.isEmpty { return data.name }
        return data.name + " \u{2022}\u{2022}" + data.last4
    }

    private var payKey: LocalizedStringKey {
        if data.hasPayable { return LocalizedStringKey("d6.card.payCard") }
        return LocalizedStringKey("cardPage.recordPayment")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: title, hidden: hidden, onToggle: onToggleHidden, onBack: onBack, onEdit: onEdit)
            hero
            actions
            ClosingSection(name: data.name, closedDay: Int(data.closedDay), closeProblem: data.closeProblem, onSetClosed: onSetClosed)
            statements
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private var hero: some View {
        TonalHero(tone: data.payableTone, hidden: hidden) {
            HeroLabel(text: "cardPage.balancePayable", tone: data.payableTone, hidden: hidden)
            HeroAmount(text: data.payableText, tone: data.payableTone, hidden: hidden)
            Text(Wording.cardStatus(data))
                .font(Font.kassa(16, .semibold, relativeTo: .subheadline))
                .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroInk(data.payableTone))
            HStack(alignment: .top, spacing: 36) {
                HeroFootItem(label: "cardPage.outstanding", value: data.outstandingText, tone: data.payableTone, hidden: hidden)
                HeroFootItem(
                    label: "cardPage.totalBalance", value: data.totalText, tone: data.payableTone, hidden: hidden,
                    color: Palette.figure(data.totalTone)
                )
                Spacer(minLength: 0)
            }
            .padding(.top, 14)
        }
    }

    @ViewBuilder
    private var actions: some View {
        HStack(spacing: 12) {
            if data.canPay {
                PagePill(title: payKey, filled: true, action: onPay)
            }
            if let correct = onCorrect {
                PagePill(title: "d6.correct", filled: false, action: correct)
                    .fixedSize(horizontal: data.canPay, vertical: false)
                    .accessibilityLabel(Text("correction.open"))
            }
        }
    }

    @ViewBuilder
    private var statements: some View {
        StatementSwitcher(
            title: Wording.cardStatementTitle(data), subtitle: Wording.cardStatementRange(data),
            canBack: data.canBack, canForward: data.canForward, onShift: onShift
        )
        HStack(spacing: 14) {
            Text(String(format: String(localized: "cardPage.charges"), data.chargesText))
                .foregroundColor(Palette.ink)
            if !data.paymentsText.isEmpty {
                Text(String(format: String(localized: "cardPage.payments"), data.paymentsText))
                    .foregroundColor(Palette.ink2)
            }
            if let left = Wording.cardLeft(data) {
                Text(left)
                    .foregroundColor(data.left.name == "LeftToPay" ? Palette.orange : Palette.green)
            }
            Spacer(minLength: 0)
        }
        .font(Font.kassa(13, .regular, relativeTo: .footnote).monospacedDigit())
        .lineLimit(1)
        if data.entryCount == 0 {
            Text(data.back == 0 ? LocalizedStringKey("cardPage.noChargesSince") : LocalizedStringKey("cardPage.nothingOnStatement"))
                .font(Font.kassa(15, .regular, relativeTo: .body))
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(data.days, id: \.epochDay) { day in
                    DaySection(day: day, today: today, onOpen: onOpen)
                }
            }
        }
    }
}

// MARK: - Person

/// A person's page: a hero with who owes whom and how much (and since when), Record a payment beside Split, and the history (splits,
/// the starting balance and payments, newest first). A payment opens its sheet to edit or delete. Without its scroll view, so a
/// screenshot can draw it.
struct PersonPageContent: View {
    let data: PersonPageData
    let hidden: Bool
    let onToggleHidden: () -> Void
    let onBack: () -> Void
    let onRecord: () -> Void
    /// A payment's entry id.
    let onOpenPayment: (Int64) -> Void
    /// "Split a transaction": pick an expense to split with this person.
    var onSplit: () -> Void = {}
    /// "Edit": rename, what was owed when added, archive (batch Q).
    var onEdit: (() -> Void)? = nil

    private var title: String {
        if data.archived { return String(format: String(localized: "personPage.archivedName"), data.name) }
        return data.name
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: title, hidden: hidden, onToggle: onToggleHidden, onBack: onBack, onEdit: onEdit)
            hero
            actions
            historyList
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private var hero: some View {
        TonalHero(tone: data.balanceTone, hidden: hidden) {
            HeroLabel(text: LocalizedStringKey(Wording.personBalanceTitle(data)), tone: data.balanceTone, hidden: hidden)
            HeroAmount(text: data.balanceText, tone: data.balanceTone, hidden: hidden)
            if data.sinceDay > 0 {
                Text(String(format: String(localized: "personPage.since"), KassaDates.shortDate(epochDay: Int(data.sinceDay))))
                    .font(Font.kassa(16, .semibold, relativeTo: .subheadline))
                    .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroInk(data.balanceTone))
            }
        }
    }

    private var actions: some View {
        HStack(spacing: 12) {
            PagePill(title: "personPage.recordPayment", filled: true, action: onRecord)
            PagePill(title: "personPage.split", filled: false, action: onSplit)
                .fixedSize(horizontal: true, vertical: false)
                .accessibilityLabel(Text("personPage.splitATransaction"))
        }
    }

    @ViewBuilder
    private var historyList: some View {
        VStack(spacing: 0) {
            SectionLabel(title: "personPage.history")
            Hairline()
        }
        if data.lines.isEmpty {
            Text("personPage.nothingYet")
                .font(Font.kassa(15, .regular, relativeTo: .body))
                .foregroundColor(Palette.ink2)
                .padding(.vertical, 12)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(data.lines.enumerated()), id: \.offset) { entry in
                    lineRow(entry.element)
                    Hairline()
                }
            }
        }
    }

    @ViewBuilder
    private func lineRow(_ line: PersonLineData) -> some View {
        if line.paymentId > 0 {
            Button(action: { onOpenPayment(line.paymentId) }) { lineContent(line) }
                .buttonStyle(.plain)
        } else {
            lineContent(line)
        }
    }

    private func lineContent(_ line: PersonLineData) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Wording.personLineNote(line, name: data.name))
                    .font(Font.kassa(16, .semibold, relativeTo: .body))
                    .lineLimit(1)
                Text(KassaDates.shortDateYear(epochDay: Int(line.day)))
                    .font(Font.kassa(13, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink2)
            }
            Spacer(minLength: 8)
            Text(line.amountText)
                .font(Font.kassa(16, .bold, relativeTo: .body).monospacedDigit())
                .lineLimit(1)
                .foregroundColor(Palette.figure(line.amountTone))
        }
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

// MARK: - The open page

/// The page that is open on the Accounts tab: a card, any other account, or a person, read from the model.
struct PageHost: View {
    @ObservedObject var model: AppModel
    let target: PageTarget

    /// The Edit sheet (account, card or person) is open.
    @State private var editing = false

    var body: some View {
        content
            .background(Palette.ground.ignoresSafeArea())
            .sheet(isPresented: $editing) {
                editSheet
            }
    }

    @ViewBuilder
    private var editSheet: some View {
        if target.isPerson {
            EditPersonSheet(model: model, personId: target.id, onClose: { editing = false })
        } else {
            EditAccountSheet(model: model, accountId: target.id, onClose: { editing = false })
        }
    }

    private func scrolled<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ScrollView {
            content()
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
        }
    }

    private var waiting: some View {
        ProgressView()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// The account or person is gone (restored over, say): only the way back.
    private var missing: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: "", hidden: model.hideAmounts, onToggle: { model.toggleHidden() }, onBack: { model.closePage() })
            Text("accountPage.missing")
                .font(.body)
                .foregroundColor(Palette.ink2)
            Spacer()
        }
        .padding(.horizontal, 16)
    }

    @ViewBuilder
    private var content: some View {
        if target.isPerson {
            personContent
        } else {
            accountContent
        }
    }

    @ViewBuilder
    private var personContent: some View {
        if let person = model.personPage, person.id == target.id {
            if person.found {
                scrolled {
                    PersonPageContent(
                        data: person, hidden: model.hideAmounts, onToggleHidden: { model.toggleHidden() },
                        onBack: { model.closePage() }, onRecord: { model.openNewPersonPayment(person) },
                        onOpenPayment: { id in model.openEntry(id: id) },
                        onSplit: { model.openSplitSheet(person) },
                        onEdit: { editing = true }
                    )
                }
            } else {
                missing
            }
        } else {
            waiting
        }
    }

    @ViewBuilder
    private var accountContent: some View {
        if let account = model.accountPage, account.id == target.id {
            if !account.found {
                missing
            } else if account.hasCardPage {
                cardContent
            } else if account.type == "INVESTMENT" {
                investContent
            } else if let loan = model.loanPage, loan.id == target.id, loan.hasTerms {
                scrolled {
                    LoanPageContent(
                        data: loan, hidden: model.hideAmounts, today: model.today, showAll: model.loanAll,
                        onToggleHidden: { model.toggleHidden() }, onBack: { model.closePage() },
                        onShift: { direction in model.shiftPage(direction) },
                        onSetClosed: { closed, finished in model.setAccountClosed(id: target.id, closed: closed, done: finished) },
                        onCorrect: { model.openCorrection(accountId: target.id) },
                        onShowAll: { all in model.setLoanAll(all) },
                        onRemoveChange: { id, finished in model.deleteLoanChange(id: id, done: finished) },
                        onOpen: { row in model.openEntry(id: row.id) },
                        onRecord: { model.openNewLoanPayment(accountId: target.id) },
                        onChangeTerms: { model.openLoanChange(accountId: target.id) },
                        onEditDetails: { model.openLoanDetails(accountId: target.id) }
                    )
                }
            } else if account.type == "LOAN" && model.loanPage == nil {
                waiting
            } else {
                scrolled {
                    AccountPageContent(
                        data: account, hidden: model.hideAmounts, today: model.today, onToggleHidden: { model.toggleHidden() },
                        onBack: { model.closePage() }, onShift: { direction in model.shiftPage(direction) },
                        onSetClosed: { closed, finished in model.setAccountClosed(id: target.id, closed: closed, done: finished) },
                        onOpen: { row in model.openEntry(id: row.id) },
                        onAddTerms: { model.openAddTerms(accountId: target.id, name: account.name) },
                        onCorrect: { model.openCorrection(accountId: target.id) },
                        onEdit: { editing = true },
                        onAddEntry: { model.openAdd(accountId: target.id) }
                    )
                }
            }
        } else {
            waiting
        }
    }

    @ViewBuilder
    private var investContent: some View {
        if let invest = model.investPage, invest.id == target.id, invest.found {
            scrolled {
                InvestPageContent(
                    data: invest, hidden: model.hideAmounts, today: model.today, onToggleHidden: { model.toggleHidden() },
                    onBack: { model.closePage() },
                    onSetClosed: { closed, finished in model.setAccountClosed(id: target.id, closed: closed, done: finished) },
                    onOpen: { row in model.openEntry(id: row.id) },
                    onOpenHolding: { holdingId in model.openHolding(accountId: target.id, holdingId: holdingId) },
                    onAddShares: { model.openHolding(accountId: target.id, holdingId: 0) },
                    onUpdatePrices: { model.openPrices(accountId: target.id) },
                    onTrade: { model.openTrade(accountId: target.id) },
                    onAnswer: { transferId in model.openAllocate(transferId: transferId) },
                    onClear: { transferId, finished in model.settleTransfer(id: transferId, done: finished) },
                    onEdit: { editing = true }
                )
            }
        } else {
            waiting
        }
    }

    @ViewBuilder
    private var cardContent: some View {
        if let card = model.cardPage, card.id == target.id, card.found {
            scrolled {
                CardPageContent(
                    data: card, hidden: model.hideAmounts, today: model.today, onToggleHidden: { model.toggleHidden() },
                    onBack: { model.closePage() }, onPay: { model.openPaySheet(cardId: card.id) },
                    onShift: { direction in model.shiftPage(direction) },
                    onSetClosed: { closed, finished in model.setAccountClosed(id: target.id, closed: closed, done: finished) },
                    onOpen: { row in model.openEntry(id: row.id) },
                    onCorrect: { model.openCorrection(accountId: target.id) },
                    onEdit: { editing = true }
                )
            }
        } else {
            waiting
        }
    }
}
