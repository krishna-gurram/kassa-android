import SwiftUI
import KassaShared

/// What the buttons of a notice do. Every choice is the person's: nothing here pays or adds by itself, and Clear adds nothing.
struct NoticeActions {
    var payCard: (Int64) -> Void = { _ in }
    var payLoan: (Int64) -> Void = { _ in }
    /// Records the bill notice for the amount (its estimate or fixed amount).
    var addBill: (NoticeData, Int64) -> Void = { _, _ in }
    var enterAmount: (NoticeData) -> Void = { _ in }
    var answer: (Int64) -> Void = { _ in }
    var track: (NoticeData) -> Void = { _ in }
    var clear: (NoticeData) -> Void = { _ in }
}

/// The Notifications page: what waits on the person, in the groups Android uses (due today, overdue, investment transfers, suggested bills).
/// It replaces Overview while open; the sheets its buttons open (pay a card, record a loan payment, answer a transfer) are the app's own.
struct NoticesScreen: View {
    @ObservedObject var model: AppModel

    @State private var confirmAll = false
    @State private var message: String?

    var body: some View {
        let items: [NoticeData] = model.notices?.items ?? []
        return ScrollView {
            NoticesContent(
                items: items, today: model.today, hidden: model.hideAmounts, message: message,
                actions: actions, onBack: { model.closeNotices() }, onClearAll: { confirmAll = true }
            )
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .alert(Text("due.clearAllTitle"), isPresented: $confirmAll) {
            Button("due.clearAll") { clear(items) }
            Button("settings.cancel", role: .cancel) {}
        } message: {
            Text("due.clearAllBody")
        }
    }

    private var actions: NoticeActions {
        var made = NoticeActions()
        made.payCard = { id in model.openPaySheet(cardId: id) }
        made.payLoan = { id in model.openNewLoanPayment(accountId: id) }
        made.addBill = { item, cents in
            model.approveBill(item, cents: cents) { outcome in show(outcome) }
        }
        made.enterAmount = { item in model.openBillAmount(item) }
        made.answer = { id in model.openAllocate(transferId: id) }
        made.track = { item in model.openBill(suggested: item) }
        made.clear = { item in clear([item]) }
        return made
    }

    private func clear(_ list: [NoticeData]) {
        model.clearNotices(list) { outcome in show(outcome) }
    }

    private func show(_ outcome: WriteOutcome) {
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

/// The page without its scroll view, so a screenshot can draw it.
struct NoticesContent: View {
    let items: [NoticeData]
    let today: Int
    let hidden: Bool
    let message: String?
    let actions: NoticeActions
    let onBack: () -> Void
    let onClearAll: () -> Void

    private func payments(overdue: Bool) -> [NoticeData] {
        var out: [NoticeData] = []
        for item in items where item.kind == "CARD" || item.kind == "LOAN" || item.kind == "BILL" {
            if item.overdue == overdue { out.append(item) }
        }
        return out
    }

    private func ofKind(_ kind: String) -> [NoticeData] {
        var out: [NoticeData] = []
        for item in items where item.kind == kind { out.append(item) }
        return out
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            top
            if items.isEmpty {
                Text("due.empty")
                    .font(.body)
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 12)
            }
            if let text = message {
                Text(text).font(.footnote).foregroundColor(Palette.orange)
            }
            group("due.group.today", payments(overdue: false))
            group("due.group.overdue", payments(overdue: true))
            group("due.group.transfers", ofKind("TRANSFER"))
            group("due.group.suggested", ofKind("SUGGEST"))
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 96)
    }

    private var top: some View {
        HStack(spacing: 4) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Palette.ink)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .accessibilityLabel(Text("a11y.back"))
            Text("due.title")
                .font(.largeTitle.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Spacer(minLength: 8)
            if !items.isEmpty {
                Button(action: onClearAll) {
                    Text("due.clearAll")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minHeight: 44)
                }
            }
        }
        .padding(.top, 8)
    }

    @ViewBuilder
    private func group(_ title: LocalizedStringKey, _ list: [NoticeData]) -> some View {
        if !list.isEmpty {
            Text(title)
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
                .foregroundColor(Palette.ink2)
                .padding(.top, 10)
            ForEach(list, id: \.key) { item in
                NoticeCard(item: item, today: today, actions: actions)
            }
        }
    }
}

/// One notice: what it is, its amount, its buttons and a Clear cross. Plain, with the components the app already has.
struct NoticeCard: View {
    let item: NoticeData
    let today: Int
    let actions: NoticeActions

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center) {
                Text(DueWording.kicker(item, today: today))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(2)
                Spacer(minLength: 8)
                Button(action: { actions.clear(item) }) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(Palette.ink2)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .accessibilityLabel(Text("due.clear"))
            }
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(DueWording.title(item))
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    Text(DueWording.subtitle(item))
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .lineLimit(2)
                }
                Spacer(minLength: 8)
                Text(item.amountText)
                    .font(.body.weight(.semibold))
                    .monospacedDigit()
                    .lineLimit(1)
            }
            HStack(spacing: 8) {
                buttons
                Spacer(minLength: 0)
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14).stroke(Palette.line, lineWidth: 1))
    }

    @ViewBuilder
    private var buttons: some View {
        switch item.kind {
        case "CARD":
            FilterChip(title: String(localized: "due.pay"), selected: true, action: { actions.payCard(item.id) })
        case "LOAN":
            FilterChip(title: String(localized: "due.pay"), selected: true, action: { actions.payLoan(item.id) })
        case "BILL":
            billButtons
        case "TRANSFER":
            FilterChip(title: String(localized: "due.answer"), selected: true, action: { actions.answer(item.id) })
        default:
            FilterChip(title: String(localized: "due.track"), selected: true, action: { actions.track(item) })
        }
    }

    @ViewBuilder
    private var billButtons: some View {
        if item.amountCents > 0 {
            FilterChip(
                title: String(format: String(localized: "due.add"), item.amountText), selected: true,
                action: { actions.addBill(item, item.amountCents) }
            )
            FilterChip(title: String(localized: "due.editAmount"), selected: false, action: { actions.enterAmount(item) })
        } else {
            FilterChip(title: String(localized: "due.enterAmount"), selected: true, action: { actions.enterAmount(item) })
        }
    }
}

/// Overview's link to the page: a tinted bar that is only there when something waits.
struct NoticesBar: View {
    let items: [NoticeData]
    let onOpen: () -> Void

    private var summary: String {
        var names: [String] = []
        for item in items.prefix(2) {
            names.append(item.kind == "TRANSFER" ? String(localized: "due.bar.transfer") : item.name)
        }
        let shown = names.joined(separator: " \u{00B7} ")
        let more = items.count - 2
        if more > 0 { return String(format: String(localized: "due.bar.more"), shown, more) }
        return shown
    }

    var body: some View {
        if items.isEmpty {
            EmptyView()
        } else {
            Button(action: onOpen) {
                HStack(spacing: 10) {
                    Text(String(items.count))
                        .font(.caption.weight(.bold))
                        .foregroundColor(Palette.ground)
                        .frame(width: 28, height: 28)
                        .background(Circle().fill(Palette.ink))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("due.bar.title")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(Palette.ink)
                        Text(summary)
                            .font(.caption)
                            .foregroundColor(Palette.ink2)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 14).fill(Palette.chip))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("noticesBar")
        }
    }
}

/// Overview's Upcoming: the next three card payments and bills (the next 30 days; a card also while overdue).
struct UpcomingSection: View {
    let rows: [UpcomingData]
    let hidden: Bool
    let onOpenCard: (Int64) -> Void

    private let shownRows = 3

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("due.upcoming")
                    .font(.caption.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
                Text("due.upcoming.next30")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            .padding(.top, 14)
            .padding(.bottom, 6)
            .overlay(alignment: .bottom) { Hairline() }
            if rows.isEmpty {
                Text("due.upcoming.empty")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .padding(.vertical, 12)
            }
            ForEach(Array(rows.prefix(shownRows).enumerated()), id: \.offset) { entry in
                row(entry.element)
                Hairline()
            }
        }
    }

    @ViewBuilder
    private func row(_ item: UpcomingData) -> some View {
        if item.kind == "CARD" {
            Button(action: { onOpenCard(item.accountId) }) { rowBody(item) }
                .buttonStyle(.plain)
        } else {
            rowBody(item)
        }
    }

    private func rowBody(_ item: UpcomingData) -> some View {
        let owed: Bool = item.kind == "CARD" && !hidden
        return HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.body.weight(.semibold))
                    .lineLimit(1)
                Text(DueWording.upcomingLine(item))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Text(item.amountText)
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .foregroundColor(owed ? Palette.orange : Palette.ink)
                .lineLimit(1)
            if item.kind == "CARD" {
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
        .padding(.vertical, 6)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }
}
