import SwiftUI
import KassaShared

/// Coach screens (layer 1): Overview's "Your week in 3 points" card, the Coach page, the weekly check-in page and Coach settings.
/// Every figure and kind comes from `LedgerView.coach`; the sentences are made in `CoachWording` from what the rows say. Nothing here
/// writes anything: "Set the budget" only opens the existing budget editor.

// MARK: Row card

/// A raised card with a round badge, a bold title, a quieter line under it and (when it opens something) a chevron.
struct CoachRowCard: View {
    let symbol: String
    let tint: TintPair
    let title: String
    let detail: String
    var chevron: Bool = true

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Badge(symbol: symbol, tint: tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Font.kassa(17, .bold, relativeTo: .body))
                    .foregroundColor(Palette.ink)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                if !detail.isEmpty {
                    Text(detail)
                        .font(Font.kassa(15, .regular, relativeTo: .subheadline))
                        .foregroundColor(Palette.ink2)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            if chevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Palette.ink3)
                    .padding(.top, 4)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Palette.raised))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Palette.line, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

func coachTint(_ tint: CoachTint) -> TintPair {
    switch tint {
    case .blue: return Palette.blue
    case .pink: return Palette.pink
    case .sage: return Palette.sage
    }
}

// MARK: Overview card

/// "Your week in 3 points" under Overview's hero: the check-in's first line, and a tap opens Coach.
struct CoachTeaserCard: View {
    let line: String
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            CoachRowCard(symbol: "sparkles", tint: Palette.sage, title: String(localized: "coach.teaser.title"), detail: line)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("coachTeaser")
    }
}

// MARK: Safe to spend

/// The safe-to-spend block: a green tonal hero (orange when short, grey while amounts are hidden), the share of the money already spoken
/// for, and what it is spoken for by.
struct CoachSafeHero: View {
    let safe: CoachSafeData
    let hidden: Bool

    private var short: Bool { return safe.state == "SHORTFALL" }

    private var tint: Color {
        if hidden { return Palette.heroGrey }
        return short ? Palette.heroOrange : Palette.heroGreen
    }

    private var ink: Color {
        if hidden { return Palette.heroGreyInk }
        return short ? Palette.heroOrangeInk : Palette.heroGreenInk
    }

    private var amount: String {
        return short ? safe.shortByText : safe.amountText
    }

    private var showsBar: Bool {
        return !hidden && safe.usedPercent >= 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(CoachWording.safeLabel(safe))
                .font(Font.kassa(14, .semibold, relativeTo: .subheadline))
                .foregroundColor(ink)
            AmountText(text: amount, hidden: hidden, size: 44, color: Palette.ink, centsColor: hidden ? Palette.ink : ink)
                .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                .padding(.top, 2)
            if showsBar {
                ShareBar(permille: Int(safe.usedPercent) * 10, color: ink, over: false)
                    .padding(.top, 8)
                Text(CoachWording.safeNote(safe))
                    .font(Font.kassa(14, .regular, relativeTo: .footnote))
                    .foregroundColor(ink)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
            }
        }
        .padding(.top, 16)
        .padding(.horizontal, 20)
        .padding(.bottom, 18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(tint))
        .accessibilityElement(children: .combine)
    }
}

// MARK: Coach page

struct CoachContent: View {
    let data: CoachData
    let hidden: Bool
    /// `CoachPrefs.insightCards` and `CoachPrefs.weeklyCheckIn`.
    let showCards: Bool
    let weeklyOn: Bool
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    var onOpenCard: (CoachTarget) -> Void = { _ in }
    var onOpenCheckIn: () -> Void = {}
    var onOpenAfford: () -> Void = {}
    var onOpenMonth: () -> Void = {}

    private var cards: [CoachCard] {
        return CoachWording.cards(data, enabled: showCards)
    }

    private var checkInLink: Bool {
        return CoachWording.checkInReady(data, weeklyOn: weeklyOn)
    }

    private var sectionLink: LocalizedStringKey? {
        if checkInLink { return "coach.checkIn.link" }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ReadTop(title: String(localized: "coach.title"), hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden)
            top
            SectionLabel(title: "coachsim.section")
                .padding(.top, 6)
            Button(action: onOpenAfford) {
                CoachRowCard(
                    symbol: "questionmark.circle", tint: Palette.blue, title: String(localized: "coachsim.afford.title"),
                    detail: String(localized: "coachsim.afford.body")
                )
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("coachAfford")
            Button(action: onOpenMonth) {
                CoachRowCard(
                    symbol: "calendar", tint: Palette.teal, title: String(localized: "coachsim.simulate.title"),
                    detail: String(localized: "coachsim.simulate.body")
                )
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("coachSimulate")
            if showCards && !hidden || checkInLink {
                SectionLabel(title: "coach.section.thisWeek", link: sectionLink, onLink: onOpenCheckIn)
                    .padding(.top, 6)
            }
            if showCards && !hidden {
                if cards.isEmpty {
                    Text("coach.empty")
                        .font(Font.kassa(15, .regular, relativeTo: .subheadline))
                        .foregroundColor(Palette.ink2)
                }
                ForEach(cards) { card in
                    if let target = card.target {
                        Button(action: { onOpenCard(target) }) {
                            CoachRowCard(symbol: card.symbol, tint: coachTint(card.tint), title: card.title, detail: card.body)
                        }
                        .buttonStyle(.plain)
                    } else {
                        CoachRowCard(symbol: card.symbol, tint: coachTint(card.tint), title: card.title, detail: card.body, chevron: false)
                    }
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 48)
    }

    @ViewBuilder
    private var top: some View {
        if !showCards {
            CoachRowCard(
                symbol: "sparkles", tint: Palette.neutral, title: String(localized: "coach.cardsOff.title"),
                detail: String(localized: "coach.cardsOff.body"), chevron: false
            )
        } else if data.safe.state == "NO_ACCOUNTS" {
            CoachRowCard(
                symbol: "wallet.pass", tint: Palette.neutral, title: String(localized: "coach.safe.noAccounts.title"),
                detail: String(localized: "coach.safe.noAccounts.body"), chevron: false
            )
        } else {
            CoachSafeHero(safe: data.safe, hidden: hidden)
            if hidden {
                CoachRowCard(
                    symbol: "eye.slash", tint: Palette.neutral, title: String(localized: "coach.hidden.title"),
                    detail: String(localized: "coach.hidden.body"), chevron: false
                )
            }
        }
    }
}

// MARK: Weekly check-in

struct CheckInContent: View {
    let text: CheckInText
    let hidden: Bool
    let onBack: () -> Void
    let onToggleHidden: () -> Void
    var onSetBudget: (CheckInText.Decision) -> Void = { _ in }
    var onNotNow: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ReadTop(title: text.weekTitle, hidden: hidden, onBack: onBack, onToggleHidden: onToggleHidden)
            if text.state == "HIDDEN" {
                CoachRowCard(
                    symbol: "eye.slash", tint: Palette.neutral, title: String(localized: "coach.hidden.title"),
                    detail: String(localized: "coach.hidden.body"), chevron: false
                )
            } else if text.state != "READY" {
                CoachRowCard(
                    symbol: "calendar", tint: Palette.neutral, title: String(localized: "coach.ci.notEnough.title"),
                    detail: String(localized: "coach.ci.notEnough.body"), chevron: false
                )
            } else {
                ready
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 48)
    }

    @ViewBuilder
    private var ready: some View {
        Text(text.headline)
            .kassa(.title)
            .foregroundColor(Palette.ink)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
        Text(text.subline)
            .font(Font.kassa(15, .regular, relativeTo: .subheadline))
            .foregroundColor(Palette.ink2)
            .fixedSize(horizontal: false, vertical: true)
        if let win = text.win {
            section(label: "coach.ci.win", item: win)
        }
        if let watch = text.watch {
            section(label: "coach.ci.watch", item: watch)
        }
        if let decision = text.decision {
            decisionSection(decision)
        }
        if text.win == nil && text.watch == nil {
            Text("coach.empty")
                .font(Font.kassa(15, .regular, relativeTo: .subheadline))
                .foregroundColor(Palette.ink2)
        }
    }

    private func section(label: LocalizedStringKey, item: CheckInText.Item) -> some View {
        return VStack(alignment: .leading, spacing: 6) {
            Hairline().padding(.bottom, 8)
            Text(label)
                .kassa(.label)
                .foregroundColor(Palette.ink2)
            Text(item.title)
                .font(Font.kassa(20, .bold, relativeTo: .title3))
                .foregroundColor(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
            Text(item.body)
                .font(Font.kassa(15, .regular, relativeTo: .subheadline))
                .foregroundColor(Palette.ink2)
                .fixedSize(horizontal: false, vertical: true)
            if !item.chip.isEmpty {
                Text(item.chip)
                    .font(Font.kassa(14, .semibold, relativeTo: .footnote))
                    .foregroundColor(Palette.heroOrangeInk)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 36)
                    .background(Capsule().fill(Palette.heroOrange))
                    .padding(.top, 4)
            }
        }
        .padding(.top, 8)
    }

    private func decisionSection(_ decision: CheckInText.Decision) -> some View {
        return VStack(alignment: .leading, spacing: 6) {
            Hairline().padding(.bottom, 8)
            Text("coach.ci.decision")
                .kassa(.label)
                .foregroundColor(Palette.ink2)
            Text(decision.title)
                .font(Font.kassa(20, .bold, relativeTo: .title3))
                .foregroundColor(Palette.ink)
                .fixedSize(horizontal: false, vertical: true)
            Text(decision.body)
                .font(Font.kassa(15, .regular, relativeTo: .subheadline))
                .foregroundColor(Palette.ink2)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 12) {
                Button(action: { onSetBudget(decision) }) { Text("coach.ci.set") }
                    .buttonStyle(InkButtonStyle(filled: true))
                    .accessibilityIdentifier("coachSetBudget")
                Button(action: onNotNow) { Text("coach.ci.notNow") }
                    .buttonStyle(InkButtonStyle(filled: false))
                    .accessibilityIdentifier("coachNotNow")
            }
            .padding(.top, 10)
            Hairline().padding(.top, 14)
        }
        .padding(.top, 8)
    }
}

// MARK: Host over the tabs

/// The Coach page or the check-in page on top of the read stack. "Set the budget" opens the existing budget editor with the suggested
/// monthly amount filled in; nothing is saved until the person saves there.
struct CoachHost: View {
    @ObservedObject var model: AppModel
    let route: ReadRoute
    /// The scene's kept copy of the "Can I afford it?" form (see `SimDraft`).
    var keptSim: Binding<String> = .constant("")
    @State private var editing: BudgetEditRequest?

    var body: some View {
        if route.isSim {
            SimHost(model: model, route: route, kept: keptSim)
        } else {
            scrollBody
        }
    }

    private var scrollBody: some View {
        ScrollView {
            if let data = model.coach {
                page(data)
                    .frame(maxWidth: 720)
                    .frame(maxWidth: .infinity)
            } else {
                ProgressView().padding(.top, 80)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .sheet(item: $editing) { request in
            BudgetEditSheet(model: model, request: request)
        }
        .sweepsDraft("budget", open: editing != nil)
    }

    @ViewBuilder
    private func page(_ data: CoachData) -> some View {
        if route == ReadRoute.checkIn {
            CheckInContent(
                text: CoachWording.checkIn(data.checkIn, dismissedWeek: model.coachDismissed),
                hidden: model.hideAmounts, onBack: { model.closeRead() }, onToggleHidden: { model.toggleHidden() },
                onSetBudget: { decision in
                    editing = BudgetEditRequest(categoryId: decision.categoryId, prefill: decision.budgetInput)
                },
                onNotNow: { model.dismissCheckIn(weekStart: Int(data.checkIn.weekStartDay)) }
            )
        } else {
            CoachContent(
                data: data, hidden: model.hideAmounts, showCards: model.coachCards, weeklyOn: model.coachWeekly,
                onBack: { model.closeRead() }, onToggleHidden: { model.toggleHidden() },
                onOpenCard: { target in model.openCoachTarget(target) },
                onOpenCheckIn: { model.openCheckIn() },
                onOpenAfford: { model.openSimAsk() },
                onOpenMonth: { model.openSimMonth() }
            )
        }
    }
}

// MARK: Settings

/// Settings > Coach settings: the two switches and "what a question sends" (the totals a question would carry, as the shared rules list
/// them). Nothing is sent today; there is no network code in Kassa.
struct CoachSettingsContent: View {
    @Binding var weekly: Bool
    @Binding var cards: Bool
    let facts: [CoachFactRowData]
    /// The cushion field's text and the currency's symbol; nil leaves the Cushion section out (a screenshot).
    var cushion: Binding<String>? = nil
    var symbol: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("coach.set.title")
                .kassa(.title)
                .padding(.top, 16)
                .padding(.bottom, 8)
                .accessibilityAddTraits(.isHeader)
            SectionLabel(title: "coach.set.phone")
            Hairline()
            switchRow(title: "coach.set.weekly", hint: "coach.set.weeklyHint", isOn: $weekly, identifier: "coachWeeklySwitch")
            Hairline()
            switchRow(title: "coach.set.cards", hint: "coach.set.cardsHint", isOn: $cards, identifier: "coachCardsSwitch")
            Hairline()
            cushionSection
            sendsCard
                .padding(.top, 20)
            Text("coach.set.server")
                .font(Font.kassa(14, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 14)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 96)
    }

    @ViewBuilder
    private var cushionSection: some View {
        if let text = cushion {
            VStack(alignment: .leading, spacing: 6) {
                Text("coachsim.cushion.title")
                    .kassa(.label)
                    .foregroundColor(Palette.ink2)
                    .padding(.top, 16)
                FormField(label: "coachsim.cushion.label", problem: nil) {
                    HStack(spacing: 6) {
                        Text(symbol).foregroundColor(Palette.ink2)
                        TextField("coachsim.cushion.hint", text: text)
                            .keyboardType(.decimalPad)
                    }
                }
                .accessibilityIdentifier("coachCushion")
                Text("coachsim.cushion.help")
                    .font(Font.kassa(14, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func switchRow(title: LocalizedStringKey, hint: LocalizedStringKey, isOn: Binding<Bool>, identifier: String) -> some View {
        return VStack(alignment: .leading, spacing: 2) {
            Toggle(isOn: isOn) {
                Text(title).font(Font.kassa(17, .medium))
            }
            .frame(minHeight: 52)
            .accessibilityIdentifier(identifier)
            Text(hint)
                .font(Font.kassa(14, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .padding(.bottom, 10)
        }
    }

    private var sendsCard: some View {
        return VStack(alignment: .leading, spacing: 8) {
            Text("coach.set.sends")
                .kassa(.label)
                .foregroundColor(Palette.ink2)
            Text("coach.set.sendsHint")
                .font(Font.kassa(14, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.ink2)
                .fixedSize(horizontal: false, vertical: true)
            if facts.isEmpty {
                Text("coach.set.noFacts")
                    .font(Font.kassa(14, .regular, relativeTo: .footnote))
                    .foregroundColor(Palette.ink2)
            }
            VStack(alignment: .leading, spacing: 2) {
                ForEach(facts, id: \.key) { fact in
                    Text(fact.key + ": " + fact.valueText)
                        .font(.system(size: 13, weight: .regular, design: .monospaced))
                        .foregroundColor(Palette.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text("coach.set.noNames")
                    .font(.system(size: 13, weight: .regular, design: .monospaced))
                    .foregroundColor(Palette.ink3)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Palette.raised))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(Palette.line, lineWidth: 1))
    }
}

/// The settings page with its scroll view, bound to the model.
struct CoachSettingsPage: View {
    @ObservedObject var model: AppModel

    var body: some View {
        let weekly = Binding<Bool>(get: { model.coachWeekly }, set: { value in model.setCoachWeekly(value) })
        let cards = Binding<Bool>(get: { model.coachCards }, set: { value in model.setCoachCards(value) })
        let cushion = Binding<String>(get: { model.simCushionText }, set: { value in model.setSimCushion(value) })
        return ScrollView {
            CoachSettingsContent(
                weekly: weekly, cards: cards, facts: model.coach?.facts ?? [], cushion: cushion,
                symbol: model.picks?.currencySymbol() ?? ""
            )
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { model.loadSimCushionText() }
    }
}
