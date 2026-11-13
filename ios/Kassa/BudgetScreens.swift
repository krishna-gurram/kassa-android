import SwiftUI
import KassaShared

/// Settings > Budgets (batch J): every top-level expense category with its budget for the current budget month, and an editor for one
/// category (an amount for every month and, optionally, one just for this month; a blank amount removes it). Every figure and rule is
/// :domain's (`BudgetRules` through `LedgerView.budgetsPage`, `budgetEdit`, `budgetPlan`); the store runs the writes in one transaction.
/// While amounts are hidden the shared rows carry the mask, no bar and no OVER or LEFT word, so a budget's state is never given away.

struct BudgetEditRequest: Identifiable {
    let id = UUID()
    let categoryId: Int64
    /// A line shown under the name (Coach's suggested weekly limit); nothing is filled in from it.
    var suggestion: String? = nil
}

struct BudgetsPage: View {
    @ObservedObject var model: AppModel
    @State private var editing: BudgetEditRequest?

    var body: some View {
        ScrollView {
            if let data = model.budgets {
                BudgetsContent(
                    data: data, hidden: model.hideAmounts,
                    onToggleHidden: { model.toggleHidden() },
                    onOpen: { id in editing = BudgetEditRequest(categoryId: id) }
                )
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            } else {
                ProgressView().padding(.top, 80)
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { model.openBudgets() }
        .onDisappear { model.closeBudgets() }
        .sheet(item: $editing) { request in
            BudgetEditSheet(model: model, request: request)
        }
    }
}

/// The list without its scroll view, so a screenshot can draw it.
struct BudgetsContent: View {
    let data: BudgetsData
    let hidden: Bool
    let onToggleHidden: () -> Void
    let onOpen: (Int64) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("budgets.title")
                    .font(.largeTitle.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Spacer(minLength: 8)
                EyeButton(hidden: hidden, onToggle: onToggleHidden)
            }
            .padding(.top, 8)
            Text(KassaDates.monthYear(year: Int(data.year), month: Int(data.month)))
                .font(.footnote.weight(.semibold))
                .foregroundColor(Palette.ink2)
            if data.hasAny {
                HStack {
                    Text("budgets.total").font(.body)
                    Spacer(minLength: 8)
                    Text(data.totalText).font(.body.weight(.semibold)).monospacedDigit().lineLimit(1)
                }
                .padding(.vertical, 12)
                .frame(minHeight: 48)
                Hairline()
            }
            if data.rows.isEmpty {
                Text("budgets.noCategories").font(.body).foregroundColor(Palette.ink2).padding(.vertical, 16)
            }
            ForEach(data.rows, id: \.categoryId) { row in
                Button(action: { onOpen(row.categoryId) }) { BudgetRow(row: row) }
                    .buttonStyle(.plain)
                Hairline()
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 96)
    }
}

struct BudgetRow: View {
    let row: BudgetRowData

    private var choice: CategoryChoiceData {
        return CategoryChoiceData(
            id: row.categoryId, name: row.name, parentName: "", iconKind: row.iconKind, iconValue: row.iconValue,
            color: row.color, hasSubcategories: false
        )
    }

    /// OVER and LEFT come from the shared rule and are empty while hidden.
    private var status: String {
        if row.statusKind == "OVER" { return String(format: String(localized: "budgets.over"), row.statusText) }
        if row.statusKind == "LEFT" { return String(format: String(localized: "budgets.left"), row.statusText) }
        return ""
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                CategoryIconView(choice: choice, size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text(row.name).font(.body).lineLimit(1)
                    Text(row.hasBudget ? String(format: String(localized: "budgets.spentOf"), row.spentText, row.budgetText) : String(localized: "budgets.none"))
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .monospacedDigit()
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                Text(status)
                    .font(.footnote.weight(.semibold))
                    .monospacedDigit()
                    .foregroundColor(row.over ? Palette.orange : Palette.ink2)
                    .lineLimit(1)
            }
            if row.hasBudget {
                ShareBar(permille: Int(row.barPermille), color: Color(argb: row.color), over: row.over)
                    .padding(.leading, 44)
            }
        }
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// The editor of one category's budget.
struct BudgetEditSheet: View {
    @ObservedObject var model: AppModel
    let request: BudgetEditRequest
    @Environment(\.dismiss) private var dismiss

    @State private var loaded: BudgetEditData?
    @State private var every = ""
    @State private var thisMonth = ""
    @State private var everyProblem: String?
    @State private var monthProblem: String?
    @State private var failure: String?
    @State private var saving = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let data = loaded {
                Text(data.name).font(.title2.weight(.bold)).lineLimit(1)
                if let hint = request.suggestion {
                    Text(hint)
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                FormField(label: "budgets.everyMonth", problem: everyProblem) {
                    TextField("budgets.amountHint", text: $every)
                        .keyboardType(.decimalPad)
                        .accessibilityIdentifier("budgetEvery")
                }
                FormField(label: LocalizedStringKey(justFor(data)), problem: monthProblem) {
                    TextField("budgets.sameAsEvery", text: $thisMonth)
                        .keyboardType(.decimalPad)
                        .accessibilityIdentifier("budgetMonth")
                }
                if let text = failure {
                    Text(text).font(.footnote).foregroundColor(Palette.orange)
                }
                Button(action: save) { Text("budgets.save") }
                    .buttonStyle(InkButtonStyle(filled: true))
                    .disabled(saving)
                    .accessibilityIdentifier("budgetSave")
                Button(action: remove) { Text("budgets.remove") }
                    .buttonStyle(InkButtonStyle(filled: false))
                    .disabled(saving)
            } else {
                ProgressView()
            }
            Spacer()
        }
        .padding(16)
        .onAppear { load() }
    }

    private func justFor(_ data: BudgetEditData) -> String {
        let month = KassaDates.monthYear(year: Int(data.year), month: Int(data.month))
        return String(format: String(localized: "budgets.justFor"), month)
    }

    private func load() {
        model.loadBudgetEdit(request.categoryId) { data in
            loaded = data
            every = data?.everyInput ?? ""
            thisMonth = data?.monthInput ?? ""
        }
    }

    private func save() {
        saving = true
        everyProblem = nil
        monthProblem = nil
        failure = nil
        model.saveBudget(categoryId: request.categoryId, every: every, thisMonth: thisMonth) { outcome in
            saving = false
            switch outcome {
            case .saved:
                dismiss()
            case .refused(let field, let message):
                if field == "EVERY_MONTH" {
                    everyProblem = message
                } else if field == "THIS_MONTH" {
                    monthProblem = message
                } else {
                    failure = message
                }
            case .failed(let message):
                failure = message
            }
        }
    }

    private func remove() {
        saving = true
        model.removeBudget(categoryId: request.categoryId) { outcome in
            saving = false
            if case .saved = outcome { dismiss() }
            if case .failed(let message) = outcome { failure = message }
            if case .refused(_, let message) = outcome { failure = message }
        }
    }
}
