import SwiftUI
import KassaShared

/// The problems a check found, by field (an `InvestField` name; the holding's id for Update prices): the sentence under that field.
func investProblemMap(_ list: [FieldProblemData]) -> [String: String] {
    var result: [String: String] = [:]
    for item in list where result[item.field] == nil {
        result[item.field] = item.message
    }
    return result
}

/// One label and figure line in a sheet's summary box.
private struct InvestFigure: View {
    let label: LocalizedStringKey
    let value: String
    var color: Color = Palette.ink

    var body: some View {
        HStack {
            Text(label)
                .font(.body)
                .foregroundColor(Palette.ink2)
            Spacer(minLength: 8)
            Text(value)
                .font(.body.weight(.semibold))
                .monospacedDigit()
                .lineLimit(1)
                .foregroundColor(color)
        }
        .frame(minHeight: 40)
        .accessibilityElement(children: .combine)
    }
}

/// A short explanation in a quiet box.
private struct InvestNote: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundColor(Palette.ink2)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(Palette.ground.opacity(0.55)))
    }
}

/// The sentence under a sheet's fields when a save was refused for the whole of it.
private struct InvestError: View {
    let text: String?

    var body: some View {
        if let message = text {
            Text(message)
                .font(.footnote)
                .foregroundColor(Palette.orange)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// A shares field, a ticker field and a price field with the typing filters of :domain (digits and one mark, capped lengths).
private enum InvestFields {
    static func shares(_ text: Binding<String>, _ changed: @escaping () -> Void) -> Binding<String> {
        return Binding<String>(
            get: { text.wrappedValue },
            set: { raw in
                text.wrappedValue = InvestBridge.shared.typedShares(raw: raw)
                changed()
            }
        )
    }

    static func ticker(_ text: Binding<String>, _ changed: @escaping () -> Void) -> Binding<String> {
        return Binding<String>(
            get: { text.wrappedValue },
            set: { raw in
                text.wrappedValue = InvestBridge.shared.typedTicker(raw: raw)
                changed()
            }
        )
    }

    static func amount(_ text: Binding<String>, _ changed: @escaping () -> Void) -> Binding<String> {
        return Binding<String>(
            get: { text.wrappedValue },
            set: { raw in
                text.wrappedValue = FieldBridge.shared.typedAmount(raw: raw)
                changed()
            }
        )
    }
}

/// How a save ended, for a sheet: closes on success, puts a refusal under its field (or at the bottom), a failure at the bottom.
private func investFinish(
    _ outcome: WriteOutcome, problems: Binding<[String: String]>, error: Binding<String?>, busy: Binding<Bool>, close: () -> Void
) {
    busy.wrappedValue = false
    switch outcome {
    case .saved:
        close()
    case .refused(let field, let message):
        if field.isEmpty {
            error.wrappedValue = message
        } else {
            var found = problems.wrappedValue
            found[field] = message
            problems.wrappedValue = found
        }
    case .failed(let text):
        error.wrappedValue = text
    }
}

// MARK: - Holding

/// A holding's sheet. "Add shares you own" (a new holding): ticker, shares and what you paid per share, no cash moving. A holding you have: its
/// figures, today's price to type, what you paid while the average cost is unknown, and Remove holding (after a question).
struct HoldingSheet: View {
    @ObservedObject var model: AppModel
    let request: HoldingSheetRequest

    @State private var ticker = ""
    @State private var shares = ""
    @State private var price: String
    @State private var paid = ""
    @State private var preview: HoldingPreviewData
    @State private var problems: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false
    @State private var confirmRemove = false

    init(model: AppModel, request: HoldingSheetRequest) {
        self.model = model
        self.request = request
        let data = request.data
        _price = State(initialValue: data.priceInput)
        _preview = State(initialValue: HoldingPreviewData(
            worthText: data.worthText, hasProfit: data.hasProfit, profitText: data.profitText, profitTone: data.profitTone
        ))
    }

    private var data: HoldingSheetData { return request.data }

    private var sheetTitle: String {
        if data.isNew { return String(localized: "invest.addSharesTitle") }
        return data.ticker
    }

    private func refreshPreview() {
        if data.isNew { return }
        model.holdingPreview(accountId: data.accountId, holdingId: data.id, price: price, paid: paid) { shown in
            preview = shown
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: sheetTitle, onBack: { model.closeHoldingSheet() })
            ScrollView {
                VStack(spacing: 12) {
                    if data.isNew {
                        newFields
                    } else {
                        existingFields
                    }
                    InvestError(text: error)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: save) { Text(saveTitle) }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            if !data.isNew {
                Button(action: { confirmRemove = true }) {
                    Text("invest.removeHolding")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.orange)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
            }
            Spacer(minLength: 8)
        }
        .background(Palette.ground.ignoresSafeArea())
        .alert(Text(String(format: String(localized: "invest.removeTitle"), data.ticker)), isPresented: $confirmRemove) {
            Button("invest.keep", role: .cancel) {}
            Button("invest.remove", role: .destructive) { remove() }
        } message: {
            Text("invest.removeBody")
        }
    }

    private var saveTitle: String {
        if data.isNew { return String(localized: "invest.addShares") }
        if data.askPaid { return String(localized: "invest.save") }
        return String(localized: "invest.savePrice")
    }

    @ViewBuilder
    private var newFields: some View {
        FormField(label: "invest.ticker", problem: problems["TICKER"]) {
            TextField("invest.ticker", text: InvestFields.ticker($ticker, { problems["TICKER"] = nil }))
                .textInputAutocapitalization(.characters)
                .disableAutocorrection(true)
        }
        FormField(label: "invest.shares", problem: problems["SHARES"]) {
            TextField("invest.shares", text: InvestFields.shares($shares, { problems["SHARES"] = nil }))
                .keyboardType(.decimalPad)
        }
        FormField(label: "invest.pricePaid", problem: problems["PRICE"]) {
            TextField("invest.pricePaid", text: InvestFields.amount($paid, { problems["PRICE"] = nil }))
                .keyboardType(.decimalPad)
        }
        InvestNote(text: String(localized: "invest.averageCostHint"))
    }

    @ViewBuilder
    private var existingFields: some View {
        VStack(spacing: 0) {
            InvestFigure(label: "invest.shares", value: data.sharesText)
            if !data.avgCostText.isEmpty {
                InvestFigure(label: "invest.averageCost", value: data.avgCostText)
            }
            InvestFigure(label: "invest.worth", value: preview.worthText)
            if preview.hasProfit {
                InvestFigure(label: "invest.profit", value: preview.profitText, color: Palette.figure(preview.profitTone))
            }
        }
        .padding(.horizontal, 12)
        .background(RoundedRectangle(cornerRadius: 14).fill(Palette.chip))
        FormField(label: "invest.priceToday", problem: problems["PRICE"]) {
            TextField("invest.priceToday", text: InvestFields.amount($price, { problems["PRICE"] = nil; refreshPreview() }))
                .keyboardType(.decimalPad)
        }
        if data.askPaid {
            FormField(label: "invest.pricePaid", problem: problems["PAID"]) {
                TextField("invest.optional", text: InvestFields.amount($paid, { problems["PAID"] = nil; refreshPreview() }))
                    .keyboardType(.decimalPad)
            }
            InvestNote(text: String(localized: "invest.averageCostHint"))
        }
    }

    private func save() {
        if busy { return }
        busy = true
        error = nil
        if data.isNew {
            model.addHoldingProblems(ticker: ticker, shares: shares, paid: paid) { found in
                writeIfClean(found, holdingId: 0)
            }
        } else {
            model.holdingProblems(price: price, paid: paid) { found in
                writeIfClean(found, holdingId: data.id)
            }
        }
    }

    private func writeIfClean(_ found: [FieldProblemData], holdingId: Int64) {
        if !found.isEmpty {
            busy = false
            problems = investProblemMap(found)
            return
        }
        problems = [:]
        model.saveHolding(accountId: data.accountId, holdingId: holdingId, ticker: ticker, shares: shares, price: price, paid: paid) { outcome in
            investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeHoldingSheet() })
        }
    }

    private func remove() {
        if busy { return }
        busy = true
        model.deleteHolding(id: data.id) { outcome in
            investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeHoldingSheet() })
        }
    }
}

// MARK: - Update prices

/// Every holding's price in one sheet, so one pass brings the account up to date. All prices save together or none.
struct PricesSheet: View {
    @ObservedObject var model: AppModel
    let request: PricesSheetRequest

    @State private var prices: [String]
    @State private var problems: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false

    init(model: AppModel, request: PricesSheetRequest) {
        self.model = model
        self.request = request
        var start: [String] = []
        for row in request.data.rows { start.append(row.priceInput) }
        _prices = State(initialValue: start)
    }

    private var ids: [String] {
        var result: [String] = []
        for row in request.data.rows { result.append(String(row.id)) }
        return result
    }

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(title: String(localized: "invest.updatePrices"), onBack: { model.closePricesSheet() })
            ScrollView {
                VStack(spacing: 12) {
                    ForEach(0..<request.data.rows.count, id: \.self) { index in
                        priceRow(index)
                    }
                    InvestError(text: error)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: save) { Text("invest.savePrices") }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
        .background(Palette.ground.ignoresSafeArea())
    }

    private func priceBinding(_ index: Int) -> Binding<String> {
        let key = String(request.data.rows[index].id)
        return Binding<String>(
            get: { prices[index] },
            set: { raw in
                prices[index] = FieldBridge.shared.typedAmount(raw: raw)
                problems[key] = nil
            }
        )
    }

    private func priceRow(_ index: Int) -> some View {
        let row = request.data.rows[index]
        return HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.ticker)
                    .font(.body.weight(.semibold))
                    .lineLimit(1)
                Text(String(format: String(localized: "invest.sharesCount"), row.sharesText))
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            FormField(label: "invest.price", problem: problems[String(row.id)]) {
                TextField("invest.price", text: priceBinding(index))
                    .keyboardType(.decimalPad)
            }
            .frame(width: 170)
        }
    }

    private func save() {
        if busy { return }
        busy = true
        error = nil
        let wanted = ids
        model.pricesProblems(accountId: request.data.accountId, ids: wanted, prices: prices) { found in
            if !found.isEmpty {
                busy = false
                problems = investProblemMap(found)
                return
            }
            problems = [:]
            model.saveHoldingPrices(accountId: request.data.accountId, ids: wanted, prices: prices) { outcome in
                investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closePricesSheet() })
            }
        }
    }
}

// MARK: - Buy or sell

/// The holdings of the account as chips: picking one fills its ticker and last price.
private struct HoldingChips: View {
    let chips: [TradeChipData]
    let ticker: String
    let onPick: (TradeChipData) -> Void

    var body: some View {
        if !chips.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(chips, id: \.ticker) { chip in
                        FilterChip(title: chip.ticker, selected: ticker == chip.ticker, action: { onPick(chip) })
                    }
                }
            }
        }
    }
}

/// A buy or a sale: cash moves inside the account and the holding grows or shrinks. A sale is of something you hold, so it is picked from the
/// chips; the first holding starts picked, as on Android. No account is chosen here: the sheet belongs to the account page that opened it.
struct TradeSheet: View {
    @ObservedObject var model: AppModel
    let request: TradeSheetRequest

    @State private var buy = true
    @State private var ticker: String
    @State private var shares = ""
    @State private var price: String
    @State private var epochDay: Int
    @State private var stage: SheetStage = .form
    @State private var preview = TradePreviewData(totalText: "", cashText: "")
    @State private var problems: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false

    init(model: AppModel, request: TradeSheetRequest) {
        self.model = model
        self.request = request
        let first: TradeChipData? = request.data.chips.first
        _ticker = State(initialValue: first?.ticker ?? "")
        _price = State(initialValue: first?.priceInput ?? "")
        _epochDay = State(initialValue: model.today)
    }

    private var data: TradeSheetData { return request.data }

    private func refreshPreview() {
        model.tradePreview(accountId: data.accountId, shares: shares, price: price) { shown in
            preview = shown
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if stage == .pickDate {
                DayPickerStage(epochDay: $epochDay, onDone: { stage = .form })
            } else {
                form
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { refreshPreview() }
    }

    private var saveTitle: String {
        if buy { return String(localized: "invest.saveBuy") }
        return String(localized: "invest.saveSale")
    }

    private var totalLine: String {
        if buy { return String(format: String(localized: "invest.costsOfCash"), preview.totalText, preview.cashText) }
        return String(format: String(localized: "invest.addsToCash"), preview.totalText)
    }

    private var form: some View {
        VStack(spacing: 0) {
            SheetBar(title: String(localized: "invest.buyOrSell"), onBack: { model.closeTradeSheet() })
            ScrollView {
                VStack(spacing: 12) {
                    HStack(spacing: 8) {
                        FilterChip(title: String(localized: "invest.buy"), selected: buy, action: { buy = true; problems = [:] })
                        FilterChip(title: String(localized: "invest.sell"), selected: !buy, action: { buy = false; problems = [:] })
                        Spacer(minLength: 0)
                    }
                    HoldingChips(chips: data.chips, ticker: ticker, onPick: pick)
                    tickerField
                    HStack(alignment: .top, spacing: 8) {
                        FormField(label: "invest.shares", problem: problems["SHARES"]) {
                            TextField("invest.shares", text: InvestFields.shares($shares, { problems["SHARES"] = nil; refreshPreview() }))
                                .keyboardType(.decimalPad)
                        }
                        FormField(label: "invest.price", problem: problems["PRICE"]) {
                            TextField("invest.price", text: InvestFields.amount($price, { problems["PRICE"] = nil; refreshPreview() }))
                                .keyboardType(.decimalPad)
                        }
                    }
                    DayChips(epochDay: epochDay, today: model.today, onChoose: { day in epochDay = day }, onOther: { stage = .pickDate })
                    if !preview.totalText.isEmpty {
                        Text(totalLine)
                            .font(.footnote)
                            .foregroundColor(Palette.ink2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    InvestError(text: error)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: save) { Text(saveTitle) }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(16)
        }
    }

    @ViewBuilder
    private var tickerField: some View {
        if buy {
            FormField(label: "invest.ticker", problem: problems["TICKER"]) {
                TextField("invest.ticker", text: InvestFields.ticker($ticker, { problems["TICKER"] = nil }))
                    .textInputAutocapitalization(.characters)
                    .disableAutocorrection(true)
            }
        } else if let text = problems["TICKER"] {
            Text(text)
                .font(.footnote)
                .foregroundColor(Palette.orange)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func pick(_ chip: TradeChipData) {
        ticker = chip.ticker
        price = chip.priceInput
        problems["TICKER"] = nil
        problems["PRICE"] = nil
        refreshPreview()
    }

    private func save() {
        if busy { return }
        busy = true
        error = nil
        model.tradeProblems(
            accountId: data.accountId, buy: buy, ticker: ticker, savedTicker: "", shares: shares, price: price
        ) { found in
            if !found.isEmpty {
                busy = false
                problems = investProblemMap(found)
                return
            }
            problems = [:]
            write()
        }
    }

    private func write() {
        if buy {
            model.buy(
                accountId: data.accountId, ticker: ticker, shares: shares, price: price, epochDay: epochDay, transferId: 0
            ) { outcome in
                investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeTradeSheet() })
            }
        } else {
            model.sell(accountId: data.accountId, ticker: ticker, shares: shares, price: price, epochDay: epochDay) { outcome in
                investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeTradeSheet() })
            }
        }
    }
}

// MARK: - An existing buy or sale

/// An existing buy or sale. With its shares recorded, everything can change and saving updates the cash and the holding together. A trade from
/// before Kassa kept shares only keeps its total, so only its date can change. Either can be deleted, after asking.
struct TradeEditSheet: View {
    @ObservedObject var model: AppModel
    let request: TradeEditRequest

    @State private var ticker: String
    @State private var shares: String
    @State private var price: String
    @State private var epochDay: Int
    @State private var stage: SheetStage = .form
    @State private var problems: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false
    @State private var confirmDelete = false

    init(model: AppModel, request: TradeEditRequest) {
        self.model = model
        self.request = request
        _ticker = State(initialValue: request.data.ticker)
        _shares = State(initialValue: request.data.sharesInput)
        _price = State(initialValue: request.data.priceInput)
        _epochDay = State(initialValue: Int(request.data.day))
    }

    private var data: TradeEditData { return request.data }

    private var sheetTitle: String {
        if data.hasTrade {
            if data.buy { return String(localized: "invest.editBuy") }
            return String(localized: "invest.editSale")
        }
        if data.buy { return String(format: String(localized: "invest.buyOf"), data.ticker, data.amountText) }
        return String(format: String(localized: "invest.saleOf"), data.ticker, data.amountText)
    }

    private var note: String {
        if !data.hasTrade { return String(format: String(localized: "invest.oldTradeNote"), data.ticker) }
        if data.buy { return String(format: String(localized: "invest.savingUpdatesBuy"), data.ticker) }
        return String(format: String(localized: "invest.savingUpdatesSale"), data.ticker)
    }

    private var saveTitle: String {
        if data.hasTrade { return String(localized: "invest.saveChanges") }
        return String(localized: "invest.saveDate")
    }

    private var deleteTitle: String {
        if data.buy { return String(localized: "invest.deleteBuy") }
        return String(localized: "invest.deleteSale")
    }

    private var deleteAlertTitle: String {
        if data.buy { return String(localized: "invest.deleteBuyTitle") }
        return String(localized: "invest.deleteSaleTitle")
    }

    private var deleteBody: String {
        if data.hasTrade {
            if data.buy { return String(format: String(localized: "invest.deleteBuyBody"), data.amountText, data.sharesText, data.ticker) }
            return String(format: String(localized: "invest.deleteSaleBody"), data.amountText, data.sharesText, data.ticker)
        }
        if data.buy { return String(format: String(localized: "invest.deleteBuyOldBody"), data.amountText, data.ticker) }
        return String(format: String(localized: "invest.deleteSaleOldBody"), data.amountText, data.ticker)
    }

    var body: some View {
        VStack(spacing: 0) {
            if stage == .pickDate {
                DayPickerStage(epochDay: $epochDay, onDone: { stage = .form })
            } else {
                form
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .alert(Text(deleteAlertTitle), isPresented: $confirmDelete) {
            Button("invest.keep", role: .cancel) {}
            Button("invest.delete", role: .destructive) { delete() }
        } message: {
            Text(deleteBody)
        }
    }

    private var form: some View {
        VStack(spacing: 0) {
            SheetBar(title: sheetTitle, onBack: { model.closeTradeEditSheet() })
            ScrollView {
                VStack(spacing: 12) {
                    if data.hasTrade {
                        FormField(label: "invest.ticker", problem: problems["TICKER"]) {
                            TextField("invest.ticker", text: InvestFields.ticker($ticker, { problems["TICKER"] = nil }))
                                .textInputAutocapitalization(.characters)
                                .disableAutocorrection(true)
                        }
                        HStack(alignment: .top, spacing: 8) {
                            FormField(label: "invest.shares", problem: problems["SHARES"]) {
                                TextField("invest.shares", text: InvestFields.shares($shares, { problems["SHARES"] = nil }))
                                    .keyboardType(.decimalPad)
                            }
                            FormField(label: "invest.price", problem: problems["PRICE"]) {
                                TextField("invest.price", text: InvestFields.amount($price, { problems["PRICE"] = nil }))
                                    .keyboardType(.decimalPad)
                            }
                        }
                    }
                    DayChips(epochDay: epochDay, today: model.today, onChoose: { day in epochDay = day }, onOther: { stage = .pickDate })
                    InvestNote(text: note)
                    InvestError(text: error)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            Button(action: save) { Text(saveTitle) }
                .buttonStyle(InkButtonStyle(filled: true))
                .disabled(busy)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            Button(action: { confirmDelete = true }) {
                Text(deleteTitle)
                    .font(.body.weight(.semibold))
                    .foregroundColor(Palette.orange)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            Spacer(minLength: 8)
        }
    }

    private func save() {
        if busy { return }
        busy = true
        error = nil
        if !data.hasTrade {
            model.moveTrade(data: data, epochDay: epochDay) { outcome in
                investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeTradeEditSheet() })
            }
            return
        }
        model.tradeProblems(
            accountId: data.accountId, buy: data.buy, ticker: ticker, savedTicker: data.ticker, shares: shares, price: price
        ) { found in
            if !found.isEmpty {
                busy = false
                problems = investProblemMap(found)
                return
            }
            problems = [:]
            model.saveTradeEdit(data: data, ticker: ticker, shares: shares, price: price, epochDay: epochDay) { outcome in
                investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeTradeEditSheet() })
            }
        }
    }

    private func delete() {
        if busy { return }
        busy = true
        model.deleteTrade(entryId: data.entryId) { outcome in
            investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeTradeEditSheet() })
        }
    }
}

// MARK: - A waiting transfer

/// After moving money into an investment account: was it left as cash, or spent on shares? Nothing is decided for the person: Decide later
/// leaves it waiting. "It's uninvested cash" and "Already recorded" only stop asking (nothing else changes); buying adds to the holdings with
/// the date starting on the day the money moved, and answers the transfer in the same transaction.
struct AllocateSheet: View {
    @ObservedObject var model: AppModel
    let request: AllocateRequest

    @State private var buying = false
    @State private var ticker = ""
    @State private var shares = ""
    @State private var price = ""
    @State private var epochDay: Int
    @State private var stage: SheetStage = .form
    @State private var preview: AllocatePreviewData?
    @State private var problems: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false

    init(model: AppModel, request: AllocateRequest) {
        self.model = model
        self.request = request
        _epochDay = State(initialValue: Int(request.data.day))
    }

    private var data: AllocateSheetData { return request.data }

    private func refreshPreview() {
        model.allocatePreview(transferId: data.transferId, shares: shares, price: price) { shown in
            preview = shown
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            if stage == .pickDate {
                DayPickerStage(epochDay: $epochDay, onDone: { stage = .form })
            } else {
                content
            }
        }
        .background(Palette.ground.ignoresSafeArea())
    }

    private var content: some View {
        VStack(spacing: 0) {
            SheetBar(title: String(format: String(localized: "invest.allocateTitle"), data.amountText), onBack: { model.closeAllocateSheet() })
            ScrollView {
                VStack(spacing: 12) {
                    Text("invest.allocateSubtitle")
                        .font(.footnote)
                        .foregroundColor(Palette.ink2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if buying {
                        buyFields
                    } else {
                        options
                    }
                    InvestError(text: error)
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
            }
            if buying {
                Button(action: save) { Text("invest.addToHoldings") }
                    .buttonStyle(InkButtonStyle(filled: true))
                    .disabled(busy)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                Button(action: { buying = false; error = nil }) {
                    Text("invest.back")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.ink2)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                Spacer(minLength: 8)
            }
        }
    }

    private func option(_ title: LocalizedStringKey, _ detail: LocalizedStringKey, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundColor(Palette.ink)
                Text(detail)
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(Palette.line, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
    }

    @ViewBuilder
    private var options: some View {
        option("invest.optionCash", "invest.optionCashSub", { settle() })
        option("invest.optionBought", "invest.optionBoughtSub", { buying = true; refreshPreview() })
        option("invest.optionRecorded", "invest.optionRecordedSub", { settle() })
        option("invest.optionLater", "invest.optionLaterSub", { model.closeAllocateSheet() })
    }

    private var previewLine: String {
        guard let shown = preview else { return String(localized: "invest.allocateLeftOver") }
        if shown.kind == "TOO_MUCH" {
            return String(format: String(localized: "invest.allocateTooMuch"), shown.costText, shown.movedText)
        }
        if shown.kind == "INVESTED" {
            return String(format: String(localized: "invest.allocateInvested"), shown.costText, shown.leftText)
        }
        return String(localized: "invest.allocateLeftOver")
    }

    private var previewColor: Color {
        guard let shown = preview else { return Palette.ink2 }
        return Palette.figure(shown.tone)
    }

    @ViewBuilder
    private var buyFields: some View {
        HoldingChips(chips: data.chips, ticker: ticker, onPick: pick)
        FormField(label: "invest.ticker", problem: problems["TICKER"]) {
            TextField("invest.ticker", text: InvestFields.ticker($ticker, { problems["TICKER"] = nil }))
                .textInputAutocapitalization(.characters)
                .disableAutocorrection(true)
        }
        HStack(alignment: .top, spacing: 8) {
            FormField(label: "invest.shares", problem: problems["SHARES"]) {
                TextField("invest.shares", text: InvestFields.shares($shares, { problems["SHARES"] = nil; refreshPreview() }))
                    .keyboardType(.decimalPad)
            }
            FormField(label: "invest.priceEach", problem: problems["PRICE"]) {
                TextField("invest.priceEach", text: InvestFields.amount($price, { problems["PRICE"] = nil; refreshPreview() }))
                    .keyboardType(.decimalPad)
            }
        }
        Text(previewLine)
            .font(.footnote)
            .foregroundColor(previewColor)
            .frame(maxWidth: .infinity, alignment: .leading)
        DayChips(epochDay: epochDay, today: model.today, onChoose: { day in epochDay = day }, onOther: { stage = .pickDate })
    }

    private func pick(_ chip: TradeChipData) {
        ticker = chip.ticker
        price = chip.priceInput
        problems["TICKER"] = nil
        problems["PRICE"] = nil
        refreshPreview()
    }

    private func settle() {
        if busy { return }
        busy = true
        model.settleTransfer(id: data.transferId) { outcome in
            investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeAllocateSheet() })
        }
    }

    private func save() {
        if busy { return }
        busy = true
        error = nil
        model.tradeProblems(
            accountId: data.accountId, buy: true, ticker: ticker, savedTicker: "", shares: shares, price: price
        ) { found in
            if !found.isEmpty {
                busy = false
                problems = investProblemMap(found)
                return
            }
            problems = [:]
            model.buy(
                accountId: data.accountId, ticker: ticker, shares: shares, price: price, epochDay: epochDay, transferId: data.transferId
            ) { outcome in
                investFinish(outcome, problems: $problems, error: $error, busy: $busy, close: { model.closeAllocateSheet() })
            }
        }
    }
}
