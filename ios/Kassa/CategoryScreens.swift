import SwiftUI
import KassaShared

/// Settings > Categories (batch Q): Expense and Income lists with their archived ones, "New category", and the editor (as Android's
/// CategoryEditor). Every rule (names, uniqueness, colours, merge) is :domain's through `LedgerView`; screens only lay out.
struct CategoryEditRequest: Identifiable {
    /// 0 for a new category of [kind].
    let id: Int64
    let kind: String
    /// The amount Coach suggests, as the field takes it; it fills "Every month" in place of the saved one. Nothing is saved until the person saves.
    var budgetPrefill: String? = nil
}

struct CategoriesPage: View {
    @ObservedObject var model: AppModel
    @State private var kind = "EXPENSE"
    @State private var editing: CategoryEditRequest?

    private var data: CategoryListData? {
        return model.picks?.categoryList(kind: kind)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("cats.title")
                    .font(.largeTitle.weight(.bold))
                    .padding(.top, 8)
                Picker("cats.title", selection: $kind) {
                    Text("cats.expense").tag("EXPENSE")
                    Text("cats.income").tag("INCOME")
                }
                .pickerStyle(.segmented)
                Button(action: { editing = CategoryEditRequest(id: 0, kind: kind) }) {
                    Label("cats.new", systemImage: "plus")
                        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                }
                .foregroundColor(Palette.ink)
                Hairline()
                if let list = data {
                    rows(list.active)
                    if !list.archived.isEmpty {
                        Text("cats.archived")
                            .font(.footnote.weight(.semibold))
                            .foregroundColor(Palette.ink2)
                            .padding(.top, 12)
                        Hairline()
                        rows(list.archived)
                    }
                } else {
                    ProgressView().padding(.top, 40)
                }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .sheet(item: $editing) { request in
            CategoryEditorSheet(model: model, request: request, onClose: { editing = nil })
        }
    }

    private func rows(_ list: [CategoryListRowData]) -> some View {
        VStack(spacing: 0) {
            ForEach(list, id: \.choice.id) { row in
                Button(action: { editing = CategoryEditRequest(id: row.choice.id, kind: kind) }) {
                    HStack(spacing: 12) {
                        CategoryIconView(choice: row.choice, size: 36)
                        Text(row.choice.name)
                            .font(.body)
                            .foregroundColor(Palette.ink)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Spacer(minLength: 8)
                        if row.subCount > 0 {
                            Text(String(format: String(localized: "cats.subCount"), Int(row.subCount)))
                                .font(.footnote)
                                .foregroundColor(Palette.ink2)
                        }
                        Image(systemName: "chevron.right")
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                    }
                    .frame(minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Hairline()
            }
        }
    }
}

/// The budget part of the category editor: "Every month" and, for a saved category, "Just for <month>". Only lays out; the typed text
/// goes to `LedgerView.categorySavePlan` on save.
struct CategoryBudgetFields: View {
    let showMonth: Bool
    let monthLabel: String
    @Binding var every: String
    @Binding var thisMonth: String
    let everyProblem: String?
    let monthProblem: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("cats.budget").font(.footnote.weight(.semibold)).foregroundColor(Palette.ink2)
            FormField(label: "budgets.everyMonth", problem: everyProblem) {
                TextField("budgets.amountHint", text: $every)
                    .keyboardType(.decimalPad)
                    .accessibilityIdentifier("categoryBudgetEvery")
            }
            if showMonth {
                FormField(label: LocalizedStringKey(String(format: String(localized: "budgets.justFor"), monthLabel)), problem: monthProblem) {
                    TextField("budgets.sameAsEvery", text: $thisMonth)
                        .keyboardType(.decimalPad)
                        .accessibilityIdentifier("categoryBudgetMonth")
                }
            }
        }
    }
}

private enum IconTab {
    case emoji
    case symbols
}

struct CategoryEditorSheet: View {
    @ObservedObject var model: AppModel
    let request: CategoryEditRequest
    let onClose: () -> Void

    @State private var started = false
    @State private var name = ""
    @State private var iconKind = "emoji"
    @State private var iconValue = ""
    @State private var color: Int32 = 0
    @State private var counts = true
    @State private var hidden = false
    @State private var tab: IconTab = .emoji
    @State private var query = ""
    @State private var typed = ""
    @State private var problem: String?
    @State private var every = ""
    @State private var thisMonth = ""
    @State private var everyProblem: String?
    @State private var monthProblem: String?
    @State private var monthLabel = ""
    @State private var busy = false
    @State private var confirmArchive = false
    @State private var mergeInto: CategoryTargetData?
    @State private var subEditing: CategoryEditRequest?

    private var data: CategoryEditData? {
        if request.id == 0 { return model.picks?.categoryNew(kind: request.kind) }
        return model.picks?.categoryEdit(categoryId: request.id)
    }

    private func currentInput(_ found: CategoryEditData) -> CategoryInput {
        let base = found.input
        return CategoryInput(
            id: base.id, kind: base.kind, parentId: base.parentId, name: name,
            iconKind: iconKind, iconValue: iconValue, color: color, counts: counts, hidden: hidden
        )
    }

    var body: some View {
        Group {
            if let found = data, found.found {
                content(found)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { start() }
        .sheet(item: $subEditing) { sub in
            CategoryEditorSheet(model: model, request: sub, onClose: { subEditing = nil })
        }
    }

    private func start() {
        if started { return }
        guard let found = data, found.found else { return }
        started = true
        name = found.input.name
        iconKind = found.input.iconKind
        iconValue = found.input.iconValue
        color = found.input.color
        counts = found.input.counts
        hidden = found.input.hidden
        tab = found.input.iconKind == "emoji" ? .emoji : .symbols
        every = request.budgetPrefill ?? ""
        if found.hasBudget && !found.isNew {
            model.loadBudgetEdit(found.input.id) { loaded in
                guard let budget = loaded, budget.found else { return }
                every = request.budgetPrefill ?? budget.everyInput
                thisMonth = budget.monthInput
                monthLabel = KassaDates.monthYear(year: Int(budget.year), month: Int(budget.month))
            }
        }
    }

    private func content(_ found: CategoryEditData) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Button("cats.close") { onClose() }
                        .foregroundColor(Palette.ink2)
                        .frame(minHeight: 48)
                    Spacer()
                    Text(found.isNew ? "cats.newTitle" : "cats.editTitle").font(.headline)
                    Spacer()
                    Button("cats.save") { save(found) }
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.ink)
                        .frame(minHeight: 48)
                        .disabled(busy)
                }
                nameField
                if found.isTop {
                    iconSection
                    colorSection(found)
                    if found.hasBudget {
                        budgetSection(found)
                    }
                    if found.input.kind == "EXPENSE" {
                        Toggle(isOn: $counts) { Text("cats.counts") }
                            .frame(minHeight: 48)
                        Text("cats.countsNote").font(.footnote).foregroundColor(Palette.ink2)
                    }
                }
                if let message = problem {
                    Text(message).font(.footnote).foregroundColor(Palette.orange)
                }
                if found.isTop && !found.isNew {
                    subsSection(found)
                }
                if !found.isTop {
                    moveSection(found)
                }
                if !found.isNew {
                    mergeSection(found)
                    archiveButton(found)
                }
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .alert(Text("cats.archiveTitle"), isPresented: $confirmArchive) {
            Button("cats.cancel", role: .cancel) {}
            Button("cats.archive", role: .destructive) { archive(found) }
        } message: {
            Text("cats.archiveBody")
        }
        .alert(Text("cats.mergeTitle"), isPresented: mergeBinding) {
            Button("cats.cancel", role: .cancel) { mergeInto = nil }
            Button("cats.merge", role: .destructive) { merge(found) }
        } message: {
            Text(String(format: String(localized: "cats.mergeBody"), found.input.name, mergeInto?.name ?? ""))
        }
    }

    private var mergeBinding: Binding<Bool> {
        return Binding<Bool>(get: { mergeInto != nil }, set: { shown in if !shown { mergeInto = nil } })
    }

    private var nameBinding: Binding<String> {
        return Binding<String>(
            get: { name },
            set: { raw in name = CategoryIconBridge.shared.typedName(raw: raw); problem = nil }
        )
    }

    private var nameField: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("cats.name").font(.footnote.weight(.semibold)).foregroundColor(Palette.ink2)
            TextField(String(localized: "cats.name"), text: nameBinding)
                .textInputAutocapitalization(.sentences)
                .padding(.horizontal, 12)
                .frame(minHeight: 48)
                .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
            Text("\(name.count) / \(Int(CategoryIconBridge.shared.nameLimit()))")
                .font(.caption)
                .foregroundColor(Palette.ink2)
        }
    }

    private var preview: CategoryChoiceData {
        return CategoryChoiceData(
            id: 0, name: name, parentName: "", iconKind: iconKind, iconValue: iconValue, color: color, hasSubcategories: false
        )
    }

    private var iconSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("cats.icon").font(.footnote.weight(.semibold)).foregroundColor(Palette.ink2)
                Spacer()
                CategoryIconView(choice: preview, size: 44)
            }
            Picker("cats.icon", selection: $tab) {
                Text("cats.emoji").tag(IconTab.emoji)
                Text("cats.icons").tag(IconTab.symbols)
            }
            .pickerStyle(.segmented)
            if tab == .emoji {
                TextField(String(localized: "cats.emojiHint"), text: typedBinding)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 48)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
                grid(CategoryIconBridge.shared.emoji(), isEmoji: true)
            } else {
                TextField(String(localized: "cats.searchIcons"), text: $query)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 48)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Palette.chip))
                grid(CategoryIconBridge.shared.icons(query: query), isEmoji: false)
            }
        }
    }

    private var typedBinding: Binding<String> {
        return Binding<String>(
            get: { typed },
            set: { raw in
                let one: String = CategoryIconBridge.shared.typedEmoji(raw: raw)
                typed = ""
                if !one.isEmpty {
                    iconKind = "emoji"
                    iconValue = one
                }
            }
        )
    }

    private func grid(_ items: [String], isEmoji: Bool) -> some View {
        let columns: [GridItem] = [GridItem(.adaptive(minimum: 48), spacing: 4)]
        return LazyVGrid(columns: columns, spacing: 4) {
            ForEach(items, id: \.self) { item in
                Button(action: { pick(item, isEmoji: isEmoji) }) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 10)
                            .stroke(selectedIcon(item, isEmoji: isEmoji) ? Palette.ink : Color.clear, lineWidth: 2)
                        if isEmoji {
                            Text(item).font(.title2)
                        } else {
                            Image(systemName: IconSymbols.symbol(for: item)).foregroundColor(Palette.ink)
                        }
                    }
                    .frame(width: 48, height: 48)
                }
                .accessibilityLabel(item)
            }
        }
    }

    private func selectedIcon(_ item: String, isEmoji: Bool) -> Bool {
        return iconValue == item && iconKind == (isEmoji ? "emoji" : "symbol")
    }

    private func pick(_ item: String, isEmoji: Bool) {
        iconKind = isEmoji ? "emoji" : "symbol"
        iconValue = item
    }

    private func colorSection(_ found: CategoryEditData) -> some View {
        let columns: [GridItem] = [GridItem(.adaptive(minimum: 48), spacing: 4)]
        let sharing: String = found.colors.first(where: { $0.color == color })?.usedBy ?? ""
        return VStack(alignment: .leading, spacing: 8) {
            Text("cats.color").font(.footnote.weight(.semibold)).foregroundColor(Palette.ink2)
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(found.colors, id: \.color) { choice in
                    Button(action: { color = choice.color; problem = nil }) {
                        ZStack {
                            Circle().fill(Color(argb: choice.color)).frame(width: 32, height: 32)
                            if color == choice.color {
                                Image(systemName: "checkmark").foregroundColor(.white).font(.footnote.weight(.bold))
                            } else if !choice.usedBy.isEmpty {
                                Circle().fill(Color.white.opacity(0.9)).frame(width: 8, height: 8)
                            }
                        }
                        .frame(width: 48, height: 48)
                    }
                    .accessibilityLabel(choice.usedBy.isEmpty ? "" : choice.usedBy)
                }
            }
            if !sharing.isEmpty {
                Text(String(format: String(localized: "cats.alsoUsedBy"), sharing))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
        }
    }

    /// Monthly budget: an amount for every month and, for a saved category, one just for this budget month. Blank removes it. The
    /// rules are :domain's (`CategoryRules.savePlan`); the amounts are saved with the category in one transaction.
    private func budgetSection(_ found: CategoryEditData) -> some View {
        CategoryBudgetFields(
            showMonth: found.hasMonthBudget, monthLabel: monthLabel, every: $every, thisMonth: $thisMonth,
            everyProblem: everyProblem, monthProblem: monthProblem
        )
    }

    private func subsSection(_ found: CategoryEditData) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("cats.subs").font(.footnote.weight(.semibold)).foregroundColor(Palette.ink2).padding(.bottom, 4)
            Hairline()
            ForEach(found.subs, id: \.id) { sub in
                Button(action: { subEditing = CategoryEditRequest(id: sub.id, kind: request.kind) }) {
                    HStack {
                        Text(sub.name)
                            .foregroundColor(sub.hidden ? Palette.ink2 : Palette.ink)
                            .lineLimit(1)
                            .truncationMode(.tail)
                        Spacer(minLength: 8)
                        if sub.hidden {
                            Text("cats.archivedTag").font(.footnote).foregroundColor(Palette.ink2)
                        }
                        Image(systemName: "chevron.right").font(.footnote).foregroundColor(Palette.ink2)
                    }
                    .frame(minHeight: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Hairline()
            }
            NewSubcategoryTile(
                ledger: model.picks, parentId: found.input.id, parentName: found.input.name,
                onAdd: model.subcategoryAdder, onAdded: { _ in }
            )
            .padding(.top, 8)
        }
    }

    private func moveSection(_ found: CategoryEditData) -> some View {
        Menu {
            ForEach(found.moveTargets, id: \.id) { target in
                Button(target.name) { move(found, to: target.id) }
            }
        } label: {
            Label("cats.moveTo", systemImage: "arrow.right")
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        }
        .foregroundColor(Palette.ink)
        .disabled(found.moveTargets.isEmpty)
    }

    private func mergeSection(_ found: CategoryEditData) -> some View {
        Menu {
            ForEach(found.mergeTargets, id: \.id) { target in
                Button(target.name) { mergeInto = target }
            }
        } label: {
            Label("cats.mergeInto", systemImage: "arrow.triangle.merge")
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        }
        .foregroundColor(Palette.ink)
        .disabled(found.mergeTargets.isEmpty)
    }

    private func archiveButton(_ found: CategoryEditData) -> some View {
        Button(action: {
            if found.input.hidden { restore(found) } else { confirmArchive = true }
        }) {
            Text(found.input.hidden ? "cats.restore" : "cats.archive")
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        }
        .foregroundColor(found.input.hidden ? Palette.ink : Palette.orange)
        .padding(.bottom, 24)
    }

    private func finish(_ outcome: WriteOutcome, closes: Bool) {
        busy = false
        switch outcome {
        case .saved:
            if closes { onClose() }
        case .refused(let field, let message):
            if field == "EVERY_MONTH" {
                everyProblem = message
            } else if field == "THIS_MONTH" {
                monthProblem = message
            } else {
                problem = message
            }
        case .failed(let message):
            problem = message
        }
    }

    private func save(_ found: CategoryEditData) {
        if busy { return }
        let input = currentInput(found)
        let found2: String = model.picks?.categoryProblem(input: input) ?? ""
        if !found2.isEmpty {
            problem = found2
            return
        }
        busy = true
        everyProblem = nil
        monthProblem = nil
        model.saveCategory(input, every: found.hasBudget ? every : nil, thisMonth: thisMonth) { outcome in finish(outcome, closes: true) }
    }

    private func archive(_ found: CategoryEditData) {
        guard let ledger = model.picks else { return }
        let input = ledger.categoryInputWith(categoryId: found.input.id, name: found.input.name, hidden: true)
        busy = true
        model.saveCategory(input) { outcome in finish(outcome, closes: true) }
    }

    private func restore(_ found: CategoryEditData) {
        guard let ledger = model.picks else { return }
        let input = ledger.categoryInputWith(categoryId: found.input.id, name: found.input.name, hidden: false)
        busy = true
        model.saveCategory(input) { outcome in finish(outcome, closes: true) }
    }

    private func move(_ found: CategoryEditData, to top: Int64) {
        guard let ledger = model.picks else { return }
        let input = ledger.movedSubcategoryInput(subId: found.input.id, toTopId: top)
        busy = true
        model.saveCategory(input) { outcome in finish(outcome, closes: true) }
    }

    private func merge(_ found: CategoryEditData) {
        guard let target = mergeInto else { return }
        mergeInto = nil
        let message: String = model.picks?.mergeProblem(fromId: found.input.id, intoId: target.id) ?? ""
        if !message.isEmpty {
            problem = message
            return
        }
        busy = true
        model.mergeCategory(from: found.input.id, into: target.id) { outcome in finish(outcome, closes: true) }
    }
}
