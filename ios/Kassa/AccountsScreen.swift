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

    var body: some View {
        ScrollView {
            AccountsContent(data: data, hidden: hidden, onToggleHidden: onToggleHidden, onAdd: onAdd)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
    }
}

/// The screen without its scroll view, so a screenshot can draw it.
struct AccountsContent: View {
    let data: AccountsData
    let hidden: Bool
    let onToggleHidden: () -> Void
    var onAdd: (() -> Void)? = nil

    /// Folded groups, by key. Closed starts folded.
    @State private var folded: Set<String> = ["CLOSED"]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            TabTop(title: "accounts.title", hidden: hidden, onToggle: onToggleHidden)
            NetWorthHero(data: data)
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
        .padding(.horizontal, 16)
        .padding(.bottom, 24)
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
            if isOpen {
                groupBody(group)
            } else {
                Hairline()
            }
        }
    }

    @ViewBuilder
    private func groupBody(_ group: AccountsGroupData) -> some View {
        if group.key == "PEOPLE" {
            peopleSummary(group)
        }
        ForEach(group.rows, id: \.id) { row in
            Hairline()
            AccountRowView(row: row)
        }
    }

    @ViewBuilder
    private func peopleSummary(_ group: AccountsGroupData) -> some View {
        Hairline()
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
            .font(.footnote)
            .foregroundColor(Palette.ink2)
            .lineLimit(1)
            .padding(.vertical, 8)
        }
    }
}

/// The Net worth hero: green, orange below zero, grey while amounts are hidden (the tone arrives as Ink then).
struct NetWorthHero: View {
    let data: AccountsData

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("accounts.netWorth")
                .font(.caption.weight(.semibold))
                .textCase(.uppercase)
                .foregroundColor(Palette.ink2)
            Text(data.netWorthText)
                .font(.largeTitle.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .foregroundColor(data.netWorthTone.name == "Orange" ? Palette.orange : Palette.ink)
            Hairline()
            HStack(alignment: .top) {
                foot("accounts.assets", data.assetsText)
                Spacer(minLength: 12)
                foot("accounts.liabilities", data.liabilitiesText)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20).fill(Palette.heroTint(data.netWorthTone)))
        .accessibilityElement(children: .combine)
    }

    private func foot(_ label: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundColor(Palette.ink2)
            Text(value)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
        }
    }
}

/// A group's header: a chevron, the uppercase name and the total, at least 44 pt tall, folding or opening the group.
struct GroupHeader: View {
    let title: String
    let total: String
    let tone: Tone
    let open: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 6) {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                    .rotationEffect(.degrees(open ? 90 : 0))
                    .frame(width: 22)
                Text(title)
                    .font(.caption.weight(.semibold))
                    .textCase(.uppercase)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
                Spacer(minLength: 8)
                if !total.isEmpty {
                    Text(total)
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .foregroundColor(Palette.figure(tone))
                        .lineLimit(1)
                }
            }
            .frame(minHeight: 44)
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
        let name = Text(row.name).font(.body)
        if row.last4.isEmpty { return name }
        let tail = Text(" \u{2022}\u{2022}" + row.last4).font(.footnote).foregroundColor(Palette.ink2)
        return name + tail
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                nameLine
                    .lineLimit(2)
                if let note = Wording.note(row) {
                    Text(note)
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                if !row.amountText.isEmpty {
                    Text(row.amountText)
                        .font(.body.weight(.semibold))
                        .monospacedDigit()
                        .foregroundColor(Palette.figure(row.amountTone))
                        .lineLimit(1)
                }
                if let line = Wording.secondLine(row) {
                    Text(line)
                        .font(.footnote)
                        .foregroundColor(Palette.figure(row.dueTone, muted: true))
                        .lineLimit(1)
                }
            }
        }
        .padding(.vertical, 10)
        .frame(minHeight: 56, alignment: .top)
        .accessibilityElement(children: .combine)
    }
}
