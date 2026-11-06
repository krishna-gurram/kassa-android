import SwiftUI
import KassaShared

/// The words of Accounts > Order (the sort names are `AccountSort`'s: NAME, BALANCE, DUE, CUSTOM).
enum OrderWording {

    static func sortKey(_ name: String) -> LocalizedStringKey {
        if name == "BALANCE" { return "accounts.sort.balance" }
        if name == "DUE" { return "accounts.sort.due" }
        if name == "CUSTOM" { return "accounts.sort.custom" }
        return "accounts.sort.name"
    }
}

/// Order accounts: Name (A to Z), Balance (largest first), Due date (soonest first) and Your order (with an Arrange button), plus
/// Reset to A to Z. Reset applies at once; the Accounts tab offers Undo, so there is no question first.
struct OrderSheet: View {
    let sort: String
    let onPick: (String) -> Void
    let onArrange: () -> Void
    let onReset: () -> Void
    let onClose: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("order.title")
                    .font(.title3.weight(.semibold))
                Text("order.note")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .padding(.top, 2)
                    .padding(.bottom, 8)
                option(title: "order.name", sub: "accounts.sort.name", selected: sort == "NAME", name: "NAME")
                option(title: "order.balance", sub: "accounts.sort.balance", selected: sort == "BALANCE", name: "BALANCE")
                option(title: "order.dueDate", sub: "order.dueSub", selected: sort == "DUE", name: "DUE")
                customOption
                Hairline()
                HStack {
                    Button(action: onReset) {
                        Text("order.reset")
                            .font(.body.weight(.semibold))
                            .foregroundColor(Palette.ink2)
                            .frame(minHeight: 48)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    Spacer(minLength: 8)
                    Button(action: onClose) {
                        Text("order.close")
                            .font(.body.weight(.semibold))
                            .foregroundColor(Palette.ink)
                            .frame(minHeight: 48)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(20)
        }
        .background(Palette.ground.ignoresSafeArea())
    }

    private func radio(_ selected: Bool) -> some View {
        return Image(systemName: selected ? "largecircle.fill.circle" : "circle")
            .font(.title3)
            .foregroundColor(selected ? Palette.ink : Palette.ink2)
            .frame(width: 28)
    }

    private func label(title: LocalizedStringKey, sub: LocalizedStringKey) -> some View {
        return VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.body.weight(.semibold))
                .foregroundColor(Palette.ink)
                .multilineTextAlignment(.leading)
            Text(sub)
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .multilineTextAlignment(.leading)
        }
        .padding(.vertical, 8)
    }

    private func option(title: LocalizedStringKey, sub: LocalizedStringKey, selected: Bool, name: String) -> some View {
        return VStack(spacing: 0) {
            Hairline()
            Button(action: { onPick(name) }) {
                HStack(spacing: 12) {
                    radio(selected)
                    label(title: title, sub: sub)
                    Spacer(minLength: 8)
                }
                .frame(minHeight: 56)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(selected ? .isSelected : [])
        }
    }

    private var arrangeKey: LocalizedStringKey {
        return sort == "CUSTOM" ? "order.edit" : "order.arrange"
    }

    private var customOption: some View {
        let selected: Bool = sort == "CUSTOM"
        return VStack(spacing: 0) {
            Hairline()
            HStack(spacing: 8) {
                Button(action: onArrange) {
                    HStack(spacing: 12) {
                        radio(selected)
                        label(title: "accounts.sort.custom", sub: "order.customSub")
                        Spacer(minLength: 8)
                    }
                    .frame(minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
                Button(action: onArrange) {
                    Text(arrangeKey)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .lineLimit(1)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 48)
                        .overlay(Capsule().stroke(Palette.line, lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// "Back to A to Z" with Undo, over the bottom of Accounts. Goes by itself after a few seconds.
struct OrderMessageBar: View {
    let message: OrderMessage
    let onUndo: () -> Void
    let onGone: (UUID) -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text("accounts.backToAz")
                .font(.subheadline)
                .foregroundColor(Palette.ground)
            Spacer(minLength: 8)
            Button(action: onUndo) {
                Text("accounts.undo")
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(Palette.ground)
                    .frame(minWidth: 48, minHeight: 48)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .background(RoundedRectangle(cornerRadius: 14).fill(Palette.ink))
        .padding(.horizontal, 16)
        .padding(.bottom, 88)
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) { onGone(message.id) }
        }
    }
}

/// Arrange mode: every group is one row; opening a group lists its accounts. Drag the handles (the list's own reorder controls) or use
/// VoiceOver's Move up and Move down. Every move is saved at once; the rules of moving are :domain's (`AccountOrderBridge`).
struct ArrangeAccountsScreen: View {
    let data: AccountsData
    let onSave: (String) -> Void
    let onDone: () -> Void

    /// The order being arranged (`AccountArrangement.encode()` text), kept here so a drop settles at once while it is saved.
    @State private var order: String
    @State private var opened: Set<String> = []

    init(data: AccountsData, onSave: @escaping (String) -> Void, onDone: @escaping () -> Void) {
        self.data = data
        self.onSave = onSave
        self.onDone = onDone
        _order = State(initialValue: data.order)
    }

    private var groupKeys: [String] {
        return AccountOrderBridge.shared.groupKeys(shown: order)
    }

    private func ids(_ key: String) -> [Int64] {
        let found: [KotlinLong] = AccountOrderBridge.shared.accountIds(shown: order, groupKey: key)
        return found.map { $0.int64Value }
    }

    private func group(_ key: String) -> AccountsGroupData? {
        return data.groups.first { $0.key == key }
    }

    private func title(_ key: String) -> String {
        guard let found = group(key) else { return key }
        return Wording.groupTitle(found)
    }

    private func name(_ id: Int64, in key: String) -> String {
        guard let found = group(key) else { return "" }
        let row: AccountRowData? = found.rows.first { $0.id == id }
        return row?.name ?? ""
    }

    private func change(_ next: String) {
        order = next
        onSave(next)
    }

    // MARK: Moves

    private func dropGroup(_ from: IndexSet, _ to: Int) {
        guard let first = from.first else { return }
        change(AccountOrderBridge.shared.dropGroup(shown: order, from: Int32(first), destination: Int32(to)))
    }

    private func dropAccount(_ key: String, _ from: IndexSet, _ to: Int) {
        guard let first = from.first else { return }
        change(AccountOrderBridge.shared.dropAccount(shown: order, groupKey: key, from: Int32(first), destination: Int32(to)))
    }

    private func moveGroup(_ key: String, _ delta: Int) {
        change(AccountOrderBridge.shared.moveGroup(shown: order, key: key, delta: Int32(delta)))
    }

    private func moveAccount(_ key: String, _ id: Int64, _ delta: Int) {
        change(AccountOrderBridge.shared.moveAccount(shown: order, groupKey: key, id: id, delta: Int32(delta)))
    }

    private func toggle(_ key: String) {
        withAnimation(.easeInOut(duration: 0.2)) {
            if opened.contains(key) {
                opened.remove(key)
            } else {
                opened.insert(key)
            }
        }
    }

    // MARK: Layout

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("arrange.title")
                    .font(.largeTitle.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Spacer(minLength: 8)
                Button(action: onDone) {
                    Text("arrange.done")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minWidth: 48, minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            Text("arrange.hint")
                .font(.footnote)
                .foregroundColor(Palette.ink2)
                .padding(.horizontal, 20)
                .padding(.bottom, 8)
            list
        }
        .background(Palette.ground.ignoresSafeArea())
    }

    private var list: some View {
        List {
            Section {
                ForEach(groupKeys, id: \.self) { key in
                    groupRow(key)
                }
                .onMove(perform: dropGroup)
            }
            ForEach(groupKeys.filter { opened.contains($0) }, id: \.self) { key in
                Section(header: Text(title(key))) {
                    ForEach(ids(key), id: \.self) { id in
                        accountRow(key, id)
                    }
                    .onMove(perform: { from, to in dropAccount(key, from, to) })
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.editMode, .constant(.active))
    }

    private func groupRow(_ key: String) -> some View {
        let isOpen: Bool = opened.contains(key)
        let count: Int = ids(key).count
        let shown: String = title(key)
        return Button(action: { toggle(key) }) {
            HStack(spacing: 8) {
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundColor(Palette.ink2)
                    .rotationEffect(.degrees(isOpen ? 90 : 0))
                    .frame(width: 22)
                Text(shown)
                    .font(.body.weight(.semibold))
                    .foregroundColor(Palette.ink)
                    .lineLimit(1)
                Text(String(count))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                Spacer(minLength: 8)
            }
            .frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(Palette.ground)
        .accessibilityHint(isOpen ? Text("a11y.fold") : Text("a11y.open"))
        .accessibilityAction(named: Text("arrange.moveUp")) { moveGroup(key, -1) }
        .accessibilityAction(named: Text("arrange.moveDown")) { moveGroup(key, 1) }
    }

    private func accountRow(_ key: String, _ id: Int64) -> some View {
        let shown: String = name(id, in: key)
        return Text(shown)
            .font(.body)
            .foregroundColor(Palette.ink)
            .lineLimit(2)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .listRowBackground(Palette.ground)
            .accessibilityAction(named: Text("arrange.moveUp")) { moveAccount(key, id, -1) }
            .accessibilityAction(named: Text("arrange.moveDown")) { moveAccount(key, id, 1) }
    }
}
