import Foundation
import KassaShared

/// The "Split a transaction" sheet of a person's page: the expenses it offers (`LedgerView.splitCandidates`: expenses you paid from an
/// account in the last 60 days, not already split with this person), newest first.
struct SplitSheetRequest: Identifiable {
    let id = UUID()
    let personId: Int64
    let personName: String
    let rows: [DatedRowData]
}

extension AppModel {

    /// Opens the sheet that picks an expense to split with the person whose page is [page] open.
    func openSplitSheet(_ page: PersonPageData) {
        let given = inputs()
        queue.async {
            guard let made = self.view else { return }
            let rows: [DatedRowData] = made.splitCandidates(
                personId: page.id, hidden: given.hidden, todayEpochDay: Int32(given.today), timeZoneId: given.zone
            )
            self.publish { self.splitSheet = SplitSheetRequest(personId: page.id, personName: page.name, rows: rows) }
        }
    }

    /// An expense was picked: the sheet closes and the entry page opens on it with this person in the split and the split panel up.
    func pickSplit(_ request: SplitSheetRequest, entryId: Int64) {
        splitSheet = nil
        openEntry(id: entryId, withPerson: request.personId)
    }

    func closeSplitSheet() {
        splitSheet = nil
    }
}
