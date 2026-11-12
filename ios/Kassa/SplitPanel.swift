import SwiftUI
import KassaShared

extension SplitPersonData: Identifiable {}

/// A person's mark: a small person symbol on a round chip.
struct PersonIconView: View {
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Palette.chip)
            Image(systemName: "person.fill")
                .font(.system(size: size * 0.46, weight: .medium))
                .foregroundColor(Palette.ink2)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// The Split row of the entry page: "Split" on the left, who shares it on the right ("Just you", "With Alex and Sam") with a chevron.
/// It opens the split panel. Only the words are here; every figure comes from the split's `SplitViewData` (:domain's `SplitRules`).
struct SplitRow: View {
    @ObservedObject var draft: EntryDraftModel

    private var who: String {
        if !draft.isShared { return String(localized: "split.justYou") }
        var names: [String] = []
        for line in draft.splitView.lines where line.personId > 0 {
            names.append(draft.view.personName(id: line.personId))
        }
        let joined: String = ListFormatter.localizedString(byJoining: names)
        return String(format: String(localized: "entry.splitWith"), joined)
    }

    var body: some View {
        let shown: SplitViewData = draft.splitView
        return Button(action: { draft.show(.split) }) {
            HStack(spacing: 8) {
                Text("split.title")
                    .font(Font.kassa(17, .semibold, relativeTo: .body))
                    .foregroundColor(Palette.ink)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 1) {
                    Text(who)
                        .font(Font.kassa(15, .regular, relativeTo: .subheadline))
                        .foregroundColor(Palette.ink2)
                        .lineLimit(1)
                    if shown.shared {
                        Text(String(format: String(localized: "split.yourShare"), shown.myShareText))
                            .font(Font.kassa(12, .regular, relativeTo: .caption))
                            .foregroundColor(shown.myShareNegative ? Palette.orange : Palette.ink2)
                            .lineLimit(1)
                    }
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Palette.ink3)
            }
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The body of the split panel: Equal or Exact, who shares it (you, then the people who exist, each with a checkbox and their share),
/// who paid, and the problem if the shares don't fit. People are added in Accounts, so only people who exist are offered. An exact share
/// is typed on the number pad.
struct SplitPanel: View {
    @ObservedObject var draft: EntryDraftModel

    var body: some View {
        if draft.sharePerson > 0 {
            ShareKeypad(draft: draft)
        } else {
            choices
        }
    }

    private var modeBinding: Binding<Int> {
        return Binding<Int>(
            get: { draft.split.exact ? 1 : 0 },
            set: { index in
                if index == 1 { draft.useExactShares() } else { draft.useEqualShares() }
            }
        )
    }

    private func line(for personId: Int64, in shown: SplitViewData) -> SplitLineData? {
        for candidate in shown.lines where candidate.personId == personId {
            return candidate
        }
        return nil
    }

    private var choices: some View {
        let shown: SplitViewData = draft.splitView
        let people: [SplitPersonData] = draft.splitPeople
        return VStack(spacing: 8) {
            KassaScroll {
                VStack(alignment: .leading, spacing: 0) {
                    if shown.shared {
                        SegmentedPill(options: ["entry.split.equal", "entry.split.exactShort"], selection: modeBinding)
                            .padding(.top, 4)
                    }
                    GroupLabel(text: String(localized: "entry.split.whoShares"))
                    Hairline()
                    youRow(shown)
                    if people.isEmpty {
                        Text("split.addPeople")
                            .font(Font.kassa(13, .regular, relativeTo: .footnote))
                            .foregroundColor(Palette.ink2)
                            .padding(.vertical, 8)
                    }
                    ForEach(people) { person in
                        personRow(person, shown: shown)
                    }
                    if shown.shared {
                        sharedSection(shown)
                    }
                }
            }
            Button(action: { draft.advance() }) { Text("split.done") }
                .buttonStyle(InkButtonStyle(filled: true))
        }
    }

    private func avatar(_ name: String) -> some View {
        return Text(String(name.prefix(1)).uppercased())
            .font(Font.kassa(17, .semibold))
            .foregroundColor(Palette.ink2)
            .frame(width: 40, height: 40)
            .background(Circle().fill(Palette.chip))
            .accessibilityHidden(true)
    }

    private func checkbox(_ on: Bool) -> some View {
        return ZStack {
            RoundedRectangle(cornerRadius: 8, style: .continuous).fill(on ? Palette.ink : Color.clear)
            RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(on ? Palette.ink : Palette.line, lineWidth: 1.5)
            if on {
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(Palette.onInk)
            }
        }
        .frame(width: 26, height: 26)
        .accessibilityHidden(true)
    }

    private func amountLabel(_ line: SplitLineData) -> some View {
        return Text(line.amountText)
            .font(Font.kassa(17, .medium, relativeTo: .body).monospacedDigit())
            .foregroundColor(line.negative ? Palette.orange : Palette.ink)
            .lineLimit(1)
    }

    /// You always share an expense you split, so the row has a ticked box that does nothing.
    private func youRow(_ shown: SplitViewData) -> some View {
        let mine: SplitLineData? = line(for: 0, in: shown)
        return VStack(spacing: 0) {
            HStack(spacing: 12) {
                avatar(String(localized: "split.you"))
                Text("split.you")
                    .font(Font.kassa(17, .medium, relativeTo: .body))
                    .foregroundColor(Palette.ink)
                Spacer(minLength: 8)
                if shown.shared, let found = mine {
                    amountLabel(found)
                }
                checkbox(true)
            }
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .accessibilityElement(children: .combine)
            Hairline()
        }
    }

    private func personRow(_ person: SplitPersonData, shown: SplitViewData) -> some View {
        let picked = draft.isSelected(person.id)
        let mine: SplitLineData? = line(for: person.id, in: shown)
        return VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button(action: { draft.toggleSplit(person.id) }) {
                    HStack(spacing: 12) {
                        avatar(person.name)
                        Text(person.name)
                            .font(Font.kassa(17, .medium, relativeTo: .body))
                            .foregroundColor(Palette.ink)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                    }
                    .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(person.name))
                .accessibilityAddTraits(picked ? [.isButton, .isSelected] : [.isButton])
                if picked, let found = mine {
                    shareAmount(found)
                }
                checkbox(picked)
            }
            Hairline()
        }
    }

    /// A person's share: plain figure; in exact mode it is tapped to type an amount.
    @ViewBuilder
    private func shareAmount(_ line: SplitLineData) -> some View {
        if draft.split.exact && line.personId > 0 {
            Button(action: { draft.startShare(line.personId) }) {
                amountLabel(line)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 44)
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Palette.line, lineWidth: 1))
                    .frame(minHeight: 48)
            }
            .buttonStyle(.plain)
        } else {
            amountLabel(line)
        }
    }

    @ViewBuilder
    private func sharedSection(_ shown: SplitViewData) -> some View {
        GroupLabel(text: String(localized: "entry.split.whoPaid"))
        payerChips(shown)
        if !shown.problem.isEmpty {
            Text(shown.problem)
                .font(Font.kassa(13, .regular, relativeTo: .footnote))
                .foregroundColor(Palette.orange)
                .padding(.top, 4)
        }
        Text(owesText(shown))
            .font(Font.kassa(13, .regular, relativeTo: .footnote))
            .foregroundColor(Palette.ink2)
            .padding(.top, 4)
    }

    private func payerChips(_ shown: SplitViewData) -> some View {
        return KassaScroll(.horizontal) {
            HStack(spacing: 8) {
                FilterChip(title: String(localized: "split.you"), selected: draft.split.paidBy == 0, action: { draft.setPayer(0) })
                ForEach(Array(shown.lines.enumerated()), id: \.offset) { entry in
                    payerChip(entry.element)
                }
            }
        }
    }

    @ViewBuilder
    private func payerChip(_ line: SplitLineData) -> some View {
        if line.personId > 0 {
            FilterChip(
                title: draft.view.personName(id: line.personId), selected: draft.split.paidBy == line.personId,
                action: { draft.setPayer(line.personId) }
            )
        }
    }

    private func owesText(_ shown: SplitViewData) -> String {
        if draft.split.paidBy > 0 {
            let name = draft.view.personName(id: draft.split.paidBy)
            return String(format: String(localized: "split.youOwe"), name, shown.payerOwedText)
        }
        return String(localized: "split.eachOwes")
    }
}

/// The pad for one person's exact share. An empty pad keeps their current share.
struct ShareKeypad: View {
    @ObservedObject var draft: EntryDraftModel

    private var name: String {
        let found = draft.view.personName(id: draft.sharePerson)
        return found.isEmpty ? String(localized: "split.their") : found
    }

    private var currentText: String {
        for line in draft.splitView.lines where line.personId == draft.sharePerson {
            return line.amountText
        }
        return ""
    }

    var body: some View {
        VStack(spacing: 10) {
            SheetBar(title: String(format: String(localized: "split.theirShare"), name), onBack: { draft.cancelShare() })
            if draft.shareTyped.isEmpty {
                Text(currentText)
                    .scaledNumeral(36)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .foregroundColor(Palette.ink2)
            } else {
                Text(draft.symbol + EntryBridge.shared.display(typed: draft.shareTyped))
                    .scaledNumeral(36)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .foregroundColor(Palette.ink)
            }
            NumberPad(decimalMark: draft.decimalMark, onKey: { draft.pressShare($0) })
            Button(action: { draft.commitShare() }) { Text("split.done") }
                .buttonStyle(InkButtonStyle(filled: true))
            Spacer(minLength: 0)
        }
    }
}
