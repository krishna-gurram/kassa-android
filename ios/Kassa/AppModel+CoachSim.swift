import Foundation
import KassaShared

/// The Coach simulator on the model: which page is open, what is typed, the what-ifs applied, and the cushion. The figures are
/// `LedgerView.coachSim`, worked out on the queue like every other page's. The simulator never writes to the store.
extension AppModel {

    // MARK: Figures

    /// Works the simulator out of [made] when one of its pages is open. Queue only.
    func computeSim(_ made: LedgerView, _ given: Inputs) -> CoachSimData? {
        guard let text = given.simWhatIfs else { return nil }
        return made.coachSim(
            hidden: given.hidden, todayEpochDay: Int32(given.today), timeZoneId: given.zone, firstWeekday: Int32(given.firstWeekday),
            whatIfs: text, cushionCents: given.simCushion
        )
    }

    /// The what-if text the page on top of the read stack needs: none for a page that is not the simulator, nothing to apply on the form
    /// (it only needs the payments that can be delayed), and the applied what-ifs for the answer and the month list.
    func simText(for route: ReadRoute?) -> String? {
        guard let page = route else { return nil }
        switch page {
        case .coachAsk: return ""
        case .coachAnswer, .coachMonth: return SimChips.text(simApplied)
        default: return nil
        }
    }

    /// The figures for [route] once they were worked out for the what-ifs it needs; nil while they are being worked out.
    func simReady(for route: ReadRoute) -> CoachSimData? {
        guard let text = simText(for: route), simFor == text else { return nil }
        return sim
    }

    /// The days a purchase or a change can be on: today to this many days on.
    var simHorizon: Int {
        return Int(CoachSimBridge.shared.HORIZON_DAYS)
    }

    /// The most what-ifs at once.
    var simMaxWhatIfs: Int {
        return Int(CoachSimBridge.shared.MAX_WHAT_IFS)
    }

    // MARK: Opening

    /// "Can I afford it?": the form, with what was typed last kept.
    func openSimAsk() {
        openRead(ReadRoute.coachAsk)
    }

    /// "Simulate my month" from the Coach page: the plain month, no what-ifs.
    func openSimMonth() {
        simApplied = []
        openRead(ReadRoute.coachMonth)
    }

    /// "See every day" on the answer: the month list with the same what-ifs.
    func openSimMonthKeeping() {
        openRead(ReadRoute.coachMonth)
    }

    /// "Check my month": the typed purchase and the extra changes become the applied what-ifs, and the answer opens.
    func checkSimMonth(symbol: String) {
        let today = KassaDates.todayEpochDay()
        var chips: [SimChip] = []
        let cents = EntryBridge.shared.cents(typed: simDraft.typed)
        if cents > 0 {
            let day = simDraft.resolvedDay(today: today, horizon: simHorizon)
            let shown = symbol + EntryBridge.shared.display(typed: simDraft.typed)
            chips.append(SimChips.spend(cents: cents, day: day, shown: shown))
        }
        chips.append(contentsOf: simDraft.extras)
        simApplied = chips
        openRead(ReadRoute.coachAnswer)
    }

    // MARK: Extras on the form

    /// Adds a change under "Or try", unless the same one is there or there is no room (the shared rules allow a few).
    func addSimExtra(_ chip: SimChip) {
        if simDraft.extras.contains(chip) { return }
        if simDraft.extras.count + 1 >= simMaxWhatIfs { return }
        simDraft.extras.append(chip)
    }

    func removeSimExtra(_ chip: SimChip) {
        simDraft.extras.removeAll { $0.id == chip.id }
    }

    // MARK: The month list

    /// A tapped what-if chip on the month list takes it away and works the month out again.
    func removeSimApplied(_ chip: SimChip) {
        simApplied.removeAll { $0.id == chip.id }
        refreshFigures()
    }

    /// "As planned": every what-if away.
    func clearSimApplied() {
        simApplied = []
        refreshFigures()
    }

    // MARK: The kept draft

    /// Takes a draft the scene kept (a scene recreation) back, only while nothing was typed since.
    func restoreSimDraft(_ kept: String) {
        if kept.isEmpty || !simDraft.isEmpty { return }
        let found = SimDraft.decode(kept, maxExtras: simMaxWhatIfs - 1)
        if found.isEmpty { return }
        simDraft = found
    }

    // MARK: Cushion

    /// The cushion field as typed text, from what is remembered (called when Coach settings opens).
    func loadSimCushionText() {
        if simCushion <= 0 {
            simCushionText = ""
            return
        }
        let shown = EntryBridge.shared.display(typed: EntryBridge.shared.typedOf(cents: simCushion))
        simCushionText = FieldBridge.shared.typedAmount(raw: shown)
    }

    /// The cushion typed so far: the field keeps the shared rules' filter, and the amount is remembered at once.
    func setSimCushion(_ raw: String) {
        let text = FieldBridge.shared.typedAmount(raw: raw)
        simCushionText = text
        let cents = EntryBridge.shared.cents(typed: text)
        simCushion = cents
        CoachPrefs(defaults: defaults).cushionCents = cents
    }
}
