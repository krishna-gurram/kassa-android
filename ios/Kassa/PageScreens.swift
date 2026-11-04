import SwiftUI
import KassaShared

/// The top of a page: a back button, the title and the eye (Hide balances).
struct PageTop: View {
    let title: String
    let hidden: Bool
    let onToggle: () -> Void
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text(title)
                .font(.title2.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Spacer(minLength: 8)
            EyeButton(hidden: hidden, onToggle: onToggle)
        }
        .padding(.top, 4)
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
/// the month shown, money in and out, Close or Reopen, and the month's entries by day with Activity's rows. A loan or investment shows
/// only this for now (their own screens come later). Without its scroll view, so a screenshot can draw it.
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

    private var title: String {
        if data.last4.isEmpty { return data.name }
        return data.name + " \u{2022}\u{2022}" + data.last4
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: title, hidden: hidden, onToggle: onToggleHidden, onBack: onBack)
            MonthSwitcher(
                label: KassaDates.monthYear(year: Int(data.year), month: Int(data.month)),
                canForward: !data.isCurrentMonth, canBack: data.hasEarlier, onShift: onShift
            )
            hero
            ClosingSection(name: data.name, closedDay: Int(data.closedDay), closeProblem: data.closeProblem, onSetClosed: onSetClosed)
            entries
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private var hero: some View {
        PageHero(tint: Palette.chip) {
            HeroCaption(text: Wording.accountBalanceTitle(data))
            HeroFigure(text: data.balanceText, color: Palette.figure(data.balanceTone))
            HStack(spacing: 8) {
                FigureBox(label: "accountPage.in", value: data.inText, color: hidden ? Palette.ink : Palette.green)
                FigureBox(label: "accountPage.out", value: data.outText, color: Palette.ink)
            }
            if data.type == "CREDIT_CARD" && data.closedDay == 0 && !data.hasCardPage {
                Text("accountPage.cardNeedsDays")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            if data.maturityDay > 0 {
                Text(String(format: String(localized: "accounts.maturesOn"), KassaDates.shortDateYear(epochDay: Int(data.maturityDay))))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
    }

    @ViewBuilder
    private var entries: some View {
        if data.entryCount == 0 {
            Text("accountPage.nothingThisMonth")
                .font(.body)
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
                    .font(.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(subtitle)
                    .font(.footnote)
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
                .frame(minWidth: 44, minHeight: 44)
        }
        .disabled(!enabled)
        .accessibilityLabel(Text(description))
    }
}

/// A card with statement days: Balance payable (what is due, and when), Total balance and Outstanding, Pay card, then statement by
/// statement the card's entries with Activity's rows. The figures, words and colours come from `LedgerView.cardPage`. Without its
/// scroll view, so a screenshot can draw it.
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

    private var title: String {
        if data.last4.isEmpty { return data.name }
        return data.name + " \u{2022}\u{2022}" + data.last4
    }

    private var tint: Color {
        if hidden { return Palette.chip }
        return data.hasPayable ? Palette.orange.opacity(0.16) : Palette.green.opacity(0.16)
    }

    private var payTitle: String {
        if data.hasPayable { return String(format: String(localized: "cardPage.pay"), data.payText) }
        return String(localized: "cardPage.recordPayment")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: title, hidden: hidden, onToggle: onToggleHidden, onBack: onBack)
            hero
            ClosingSection(name: data.name, closedDay: Int(data.closedDay), closeProblem: data.closeProblem, onSetClosed: onSetClosed)
            statements
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private var hero: some View {
        PageHero(tint: tint) {
            HeroCaption(text: String(localized: "cardPage.balancePayable"))
            HeroFigure(text: data.payableText, color: Palette.figure(data.payableTone))
            Text(Wording.cardStatus(data))
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Palette.figure(data.statusTone, muted: true))
            HStack(spacing: 8) {
                FigureBox(label: "cardPage.totalBalance", value: data.totalText, color: Palette.figure(data.totalTone))
                FigureBox(label: "cardPage.outstanding", value: data.outstandingText, color: Palette.ink)
            }
            if data.canPay {
                Button(action: onPay) { Text(payTitle) }
                    .buttonStyle(InkButtonStyle(filled: true))
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
        .font(.footnote)
        .lineLimit(1)
        if data.entryCount == 0 {
            Text(data.back == 0 ? LocalizedStringKey("cardPage.noChargesSince") : LocalizedStringKey("cardPage.nothingOnStatement"))
                .font(.body)
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

/// A person's page: who owes whom and how much (and since when), Record a payment, and the history (splits, the starting balance and
/// payments, newest first). A payment opens its sheet to edit or delete. Without its scroll view, so a screenshot can draw it.
struct PersonPageContent: View {
    let data: PersonPageData
    let hidden: Bool
    let onToggleHidden: () -> Void
    let onBack: () -> Void
    let onRecord: () -> Void
    /// A payment's entry id.
    let onOpenPayment: (Int64) -> Void

    private var title: String {
        if data.archived { return String(format: String(localized: "personPage.archivedName"), data.name) }
        return data.name
    }

    private var tint: Color {
        if hidden { return Palette.chip }
        return data.side.name == "YouOwe" ? Palette.orange.opacity(0.16) : Palette.green.opacity(0.16)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageTop(title: title, hidden: hidden, onToggle: onToggleHidden, onBack: onBack)
            hero
            historyList
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
    }

    private var hero: some View {
        PageHero(tint: tint) {
            HeroCaption(text: Wording.personBalanceTitle(data))
            HeroFigure(text: data.balanceText, color: Palette.figure(data.balanceTone))
            if data.sinceDay > 0 {
                Text(String(format: String(localized: "personPage.since"), KassaDates.shortDate(epochDay: Int(data.sinceDay))))
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Palette.ink2)
            }
            Button(action: onRecord) { Text("personPage.recordPayment") }
                .buttonStyle(InkButtonStyle(filled: true))
        }
    }

    @ViewBuilder
    private var historyList: some View {
        Text("personPage.history")
            .font(.caption.weight(.semibold))
            .textCase(.uppercase)
            .foregroundColor(Palette.ink2)
            .padding(.top, 8)
        Hairline()
        if data.lines.isEmpty {
            Text("personPage.nothingYet")
                .font(.body)
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
                    .font(.body)
                    .lineLimit(1)
                Text(KassaDates.shortDateYear(epochDay: Int(line.day)))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            Spacer(minLength: 8)
            Text(line.amountText)
                .font(.body.weight(.semibold))
                .monospacedDigit()
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

    var body: some View {
        content
            .background(Palette.ground.ignoresSafeArea())
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
                        onOpenPayment: { id in model.openEntry(id: id) }
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
            } else {
                scrolled {
                    AccountPageContent(
                        data: account, hidden: model.hideAmounts, today: model.today, onToggleHidden: { model.toggleHidden() },
                        onBack: { model.closePage() }, onShift: { direction in model.shiftPage(direction) },
                        onSetClosed: { closed, finished in model.setAccountClosed(id: target.id, closed: closed, done: finished) },
                        onOpen: { row in model.openEntry(id: row.id) }
                    )
                }
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
                    onOpen: { row in model.openEntry(id: row.id) }
                )
            }
        } else {
            waiting
        }
    }
}
