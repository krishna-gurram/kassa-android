import SwiftUI
import KassaShared

/// What you have, what you owe, and when: the Net worth hero, then a foldable group for each kind of account, People, and a
/// folded Closed group. Read-only. Every figure, colour and order comes from the shared rules (`LedgerView.accounts`); this only
/// lays it out.
struct AccountsScreen: View {
    let data: AccountsData
    let hidden: Bool
    let onToggleHidden: () -> Void
    /// Opens the Add account sheet. Nil leaves the button out.
    var onAdd: (() -> Void)? = nil
    /// A tap on a row: the row and whether it is a person. Nil draws rows that do nothing.
    var onOpenRow: ((AccountRowData, Bool) -> Void)? = nil
    /// Order, Collapse all and the saved folds (batch R). Nil leaves them out.
    var order: AccountsOrderHooks? = nil

    var body: some View {
        ScrollView {
            AccountsContent(data: data, hidden: hidden, onToggleHidden: onToggleHidden, onAdd: onAdd, onOpenRow: onOpenRow, order: order)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// What Accounts > Order needs from the app: the saved folds and what the Order sheet's buttons do.
struct AccountsOrderHooks {
    let savedFolded: [String]
    let onFolded: ([String]) -> Void
    let onPick: (String) -> Void
    let onArrange: () -> Void
    let onReset: () -> Void
}

/// The screen without its scroll view, so a screenshot can draw it.
struct AccountsContent: View {
    let data: AccountsData
    let hidden: Bool
    let onToggleHidden: () -> Void
    var onAdd: (() -> Void)? = nil
    var onOpenRow: ((AccountRowData, Bool) -> Void)? = nil
    var order: AccountsOrderHooks? = nil

    /// Folded groups, by key. Closed and the archived groups start folded; with `order` the saved folds replace this on appearing.
    @State private var folded: Set<String> = Set(PhonePrefs.defaultFolded)
    @State private var ordering = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TabTop(title: "accounts.title", hidden: hidden, onToggle: onToggleHidden)
            NetWorthHero(data: data, hidden: hidden)
            orderBar
            ForEach(data.groups, id: \.key) { group in
                groupView(group)
            }
            if let add = onAdd {
                Button(action: add) {
                    Label("accounts.addAccount", systemImage: "plus")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.plain)
                .padding(.top, 8)
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 96)
        .onAppear {
            if let hooks = order { folded = Set(hooks.savedFolded) }
        }
        .onChange(of: folded) { value in
            order?.onFolded(Array(value))
        }
        .sheet(isPresented: $ordering) {
            OrderSheet(
                sort: data.sort,
                onPick: { name in ordering = false; order?.onPick(name) },
                onArrange: { ordering = false; order?.onArrange() },
                onReset: { ordering = false; order?.onReset() },
                onClose: { ordering = false }
            )
        }
    }

    // MARK: Order

    private var allFolded: Bool {
        for group in data.groups where !folded.contains(group.key) { return false }
        return true
    }

    private var foldAllKey: LocalizedStringKey {
        return allFolded ? "accounts.expandAll" : "accounts.collapseAll"
    }

    private func toggleAll() {
        withAnimation(.easeInOut(duration: 0.2)) {
            if allFolded {
                folded = []
            } else {
                folded = Set(data.groups.map { $0.key })
            }
        }
    }

    /// "Order  A to Z" with a chevron, and Collapse all at the end of the line.
    @ViewBuilder
    private var orderBar: some View {
        if order != nil && !data.arrangeKeys.isEmpty {
            HStack(spacing: 8) {
                Button(action: { ordering = true }) {
                    HStack(spacing: 6) {
                        Text("accounts.order")
                            .font(Font.kassa(15, .regular, relativeTo: .subheadline))
                            .foregroundColor(Palette.ink2)
                        Text(OrderWording.sortKey(data.sort))
                            .font(Font.kassa(15, .bold, relativeTo: .subheadline))
                            .foregroundColor(Palette.ink)
                            .lineLimit(1)
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(Palette.ink2)
                    }
                    .frame(minHeight: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(Text("accounts.changeOrder"))
                Spacer(minLength: 8)
                Button(action: toggleAll) {
                    Text(foldAllKey)
                        .font(Font.kassa(15, .semibold, relativeTo: .subheadline))
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func toggle(_ key: String) {
        withAnimation(.easeInOut(duration: 0.2)) {
            if folded.contains(key) {
                folded.remove(key)
            } else {
                folded.insert(key)
            }
        }
    }

    @ViewBuilder
    private func groupView(_ group: AccountsGroupData) -> some View {
        let isOpen = !folded.contains(group.key)
        VStack(spacing: 0) {
            GroupHeader(
                title: Wording.groupTitle(group), total: group.totalText, tone: group.totalTone, open: isOpen,
                onToggle: { toggle(group.key) }
            )
            Hairline()
            if isOpen {
                groupBody(group)
            }
        }
        .padding(.top, 10)
    }

    @ViewBuilder
    private func groupBody(_ group: AccountsGroupData) -> some View {
        if group.key == "PEOPLE" {
            peopleSummary(group)
        }
        ForEach(group.rows, id: \.id) { row in
            rowButton(row, isPerson: group.key == "PEOPLE" || group.key == "ARCHIVED_PEOPLE")
            Hairline()
        }
    }

    @ViewBuilder
    private func rowButton(_ row: AccountRowData, isPerson: Bool) -> some View {
        if let open = onOpenRow {
            Button(action: { open(row, isPerson) }) { AccountRowView(row: row) }
                .buttonStyle(.plain)
        } else {
            AccountRowView(row: row)
        }
    }

    @ViewBuilder
    private func peopleSummary(_ group: AccountsGroupData) -> some View {
        if group.rows.isEmpty {
            Text("accounts.noPeople")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
        } else {
            HStack(spacing: 14) {
                Text(String(format: String(localized: "accounts.receivable"), data.receivableText))
                Text(String(format: String(localized: "accounts.payable"), data.payableText))
                Spacer(minLength: 0)
            }
            .font(Font.kassa(13, .regular, relativeTo: .footnote))
            .foregroundColor(Palette.ink2)
            .lineLimit(1)
            .padding(.vertical, 8)
        }
    }
}

/// The Net worth hero: green, orange below zero, grey while amounts are hidden (the tone arrives as Ink then).
struct NetWorthHero: View {
    let data: AccountsData
    var hidden: Bool = false

    var body: some View {
        let tone: Tone = data.netWorthTone
        let negative: Bool = tone.name == "Orange"
        return TonalHero(tone: tone, hidden: hidden) {
            HeroLabel(text: "accounts.netWorth", tone: tone, hidden: hidden)
            AmountText(
                text: data.netWorthText, hidden: hidden, size: 52,
                color: negative ? Palette.orange : Palette.ink,
                centsColor: hidden ? Palette.ink : (negative ? Palette.orange : Palette.heroInk(tone))
            )
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .padding(.top, 4)
            HStack(alignment: .top, spacing: 36) {
                foot("accounts.assets", data.assetsText, tone)
                foot("accounts.liabilities", data.liabilitiesText, tone)
                Spacer(minLength: 0)
            }
            .padding(.top, 14)
        }
        .accessibilityElement(children: .combine)
    }

    private func foot(_ label: LocalizedStringKey, _ value: String, _ tone: Tone) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .kassa(.label)
                .foregroundColor(hidden ? Palette.heroGreyInk : Palette.heroInk(tone))
                .lineLimit(1)
            Text(value)
                .font(Font.kassa(20, .semibold, relativeTo: .title3).monospacedDigit())
                .foregroundColor(Palette.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }
}

/// A group's header: the uppercase name with a fold arrow and the total, at least 48 pt tall, folding or opening the group.
struct GroupHeader: View {
    let title: String
    let total: String
    let tone: Tone
    let open: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 6) {
                Text(title)
                    .kassa(.label)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                Image(systemName: "chevron.right")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(Palette.ink2)
                    .rotationEffect(.degrees(open ? 90 : 0))
                Spacer(minLength: 8)
                if !total.isEmpty {
                    Text(total)
                        .font(Font.kassa(15, .semibold, relativeTo: .subheadline).monospacedDigit())
                        .foregroundColor(Palette.figure(tone, muted: true))
                        .lineLimit(1)
                }
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(open ? Text("a11y.fold") : Text("a11y.open"))
    }
}

/// One account or person: the name and one main figure, with a small line under each.
struct AccountRowView: View {
    let row: AccountRowData

    private var nameLine: Text {
        let name = Text(row.name).font(Font.kassa(17, .semibold, relativeTo: .body))
        if row.last4.isEmpty { return name }
        let tail = Text(" \u{2022}\u{2022}" + row.last4).font(Font.kassa(13, .regular, relativeTo: .footnote)).foregroundColor(Palette.ink2)
        return name + tail
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                nameLine
                    .lineLimit(2)
                if let note = Wording.note(row) {
                    Text(note)
                        .font(Font.kassa(14, .regular, relativeTo: .footnote))
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                if !row.amountText.isEmpty {
                    Text(row.amountText)
                        .font(Font.kassa(20, .semibold, relativeTo: .title3))
                        .monospacedDigit()
                        .foregroundColor(Palette.figure(row.amountTone))
                        .lineLimit(1)
                }
                if let line = Wording.secondLine(row) {
                    Text(line)
                        .font(Font.kassa(14, .regular, relativeTo: .footnote))
                        .foregroundColor(Palette.figure(row.dueTone, muted: true))
                        .lineLimit(1)
                }
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(Palette.ink3)
                .frame(minHeight: 24)
                .padding(.top, 4)
                .accessibilityHidden(true)
        }
        .padding(.vertical, 12)
        .frame(minHeight: 64, alignment: .top)
        .accessibilityElement(children: .combine)
    }
}
