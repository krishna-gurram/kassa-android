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

/// The Split row of the entry page: who shares the expense and your share of it. It opens the split panel. Only the words are here;
/// every figure comes from the split's `SplitViewData` (:domain's `SplitRules`).
struct SplitRow: View {
    @ObservedObject var draft: EntryDraftModel

    private var who: String {
        if !draft.isShared { return String(localized: "split.justYou") }
        var names: [String] = [String(localized: "split.you")]
        for line in draft.splitView.lines where line.personId > 0 {
            names.append(draft.view.personName(id: line.personId))
        }
        return names.joined(separator: ", ")
    }

    var body: some View {
        let shown: SplitViewData = draft.splitView
        let on = draft.panel == .split
        return Button(action: { draft.show(.split) }) {
            HStack(spacing: 10) {
                Text("split.title")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                    .frame(minWidth: 44, alignment: .leading)
                VStack(alignment: .leading, spacing: 1) {
                    Text(who)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .lineLimit(1)
                    if shown.shared {
                        Text(String(format: String(localized: "split.yourShare"), shown.myShareText))
                            .font(.caption)
                            .foregroundColor(shown.myShareNegative ? Palette.orange : Palette.ink2)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip.opacity(0.6)))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(on ? Palette.ink : Palette.line, lineWidth: on ? 2 : 0.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The bottom panel of a split: who shares it, who paid, equal or exact shares, and each person's share. People are added in
/// Accounts, so only people who exist are offered. An exact share is typed on the number pad.
struct SplitPanel: View {
    @ObservedObject var draft: EntryDraftModel

    var body: some View {
        if draft.sharePerson > 0 {
            ShareKeypad(draft: draft)
        } else {
            choices
        }
    }

    private var choices: some View {
        let shown: SplitViewData = draft.splitView
        let people: [SplitPersonData] = draft.splitPeople
        return VStack(spacing: 8) {
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    GroupLabel(text: String(localized: "split.with"))
                    if people.isEmpty {
                        Text("split.addPeople")
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                    }
                    ForEach(people) { person in
                        personRow(person)
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

    private func personRow(_ person: SplitPersonData) -> some View {
        let picked = draft.isSelected(person.id)
        return Button(action: { draft.toggleSplit(person.id) }) {
            HStack(spacing: 10) {
                PersonIconView(size: 32)
                Text(person.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Palette.ink)
                    .lineLimit(1)
                Spacer(minLength: 0)
                if picked {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(Palette.ink)
                }
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip.opacity(0.6)))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(picked ? Palette.ink : Color.clear, lineWidth: 2))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(person.name))
        .accessibilityAddTraits(picked ? [.isButton, .isSelected] : [.isButton])
    }

    @ViewBuilder
    private func sharedSection(_ shown: SplitViewData) -> some View {
        GroupLabel(text: String(localized: "split.paidBy"))
        payerChips(shown)
        GroupLabel(text: String(localized: "split.shares"))
        HStack(spacing: 8) {
            FilterChip(title: String(localized: "split.equally"), selected: !draft.split.exact, action: { draft.useEqualShares() })
            FilterChip(title: String(localized: "split.exact"), selected: draft.split.exact, action: { draft.useExactShares() })
            Spacer(minLength: 0)
        }
        ForEach(Array(shown.lines.enumerated()), id: \.offset) { entry in
            shareLine(entry.element)
        }
        if !shown.problem.isEmpty {
            Text(shown.problem)
                .font(.footnote)
                .foregroundColor(Palette.orange)
        }
        Text(owesText(shown))
            .font(.footnote)
            .foregroundColor(Palette.ink2)
            .padding(.top, 2)
    }

    private func payerChips(_ shown: SplitViewData) -> some View {
        return ScrollView(.horizontal, showsIndicators: false) {
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

    /// One share: the name, its percent of the total and the amount. Exact shares of other people are tapped to type an amount.
    private func shareLine(_ line: SplitLineData) -> some View {
        let name = line.personId > 0 ? draft.view.personName(id: line.personId) : String(localized: "split.you")
        let editable = draft.split.exact && line.personId > 0
        let content = HStack(spacing: 8) {
            Text(name)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Palette.ink)
                .lineLimit(1)
            Spacer(minLength: 0)
            Text(line.percentText)
                .font(.caption)
                .foregroundColor(Palette.ink2)
            Text(line.amountText)
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .foregroundColor(line.negative ? Palette.orange : Palette.ink)
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, minHeight: 48)
        .background(RoundedRectangle(cornerRadius: 10).fill(Palette.ground))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(editable ? Palette.line : Color.clear, lineWidth: 1))
        return shareButton(content, line: line, editable: editable)
    }

    @ViewBuilder
    private func shareButton<Content: View>(_ content: Content, line: SplitLineData, editable: Bool) -> some View {
        if editable {
            Button(action: { draft.startShare(line.personId) }) { content }
                .buttonStyle(.plain)
        } else {
            content
        }
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
