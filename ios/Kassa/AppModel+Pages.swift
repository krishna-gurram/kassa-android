import Foundation
import KassaShared

/// The account or person page that is open on the Accounts tab. [id] is an account's or a person's own id.
struct PageTarget: Equatable {
    let isPerson: Bool
    let id: Int64
}

/// What an open page shows, worked out on the queue: an account page (and, for a card with statement days, its card page), or a
/// person page.
struct PagesData {
    let account: AccountPageData?
    let card: CardPageData?
    let person: PersonPageData?
    /// Set for a loan with terms, beside [account].
    var loan: LoanPageData? = nil
    /// Set for an investment account, beside [account].
    var invest: InvestPageData? = nil
}

/// The pay-card sheet that is open, with what it opens on (`LedgerView.cardPayData`).
struct PaySheetRequest: Identifiable {
    let id = UUID()
    let data: CardPayData
}

/// The person payment sheet that is open. [editing] is nil for a new payment; [amountText] is the edited payment's amount as
/// Money.format writes it (the mask while amounts are hidden), for the delete question. [defaultKind] and [suggestedCents] are what a
/// new payment starts on (settling the balance).
struct PersonSheetRequest: Identifiable {
    let id = UUID()
    let personId: Int64
    let personName: String
    let defaultKind: String
    let suggestedCents: Int64
    let editing: PersonPaymentEditData?
    let amountText: String
}

/// Navigation and sheets of the card, account and person pages (stage F). The figures are `LedgerView`'s, worked out with the
/// others in `AppModel.compute`; this only keeps which page is open and how far back it is.
extension AppModel {

    /// Works the open page out of [made]. Queue only.
    func computePages(_ made: LedgerView, _ given: Inputs) -> PagesData {
        guard let target = given.target else { return PagesData(account: nil, card: nil, person: nil) }
        let day = Int32(given.today)
        if target.isPerson {
            let person: PersonPageData = made.personPage(personId: target.id, hidden: given.hidden, timeZoneId: given.zone)
            return PagesData(account: nil, card: nil, person: person)
        }
        let account: AccountPageData = made.accountPage(
            accountId: target.id, hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone, monthsBack: Int32(given.pageBack)
        )
        var card: CardPageData? = nil
        if account.hasCardPage {
            card = made.cardPage(
                cardId: target.id, hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone, back: Int32(given.pageBack)
            )
        }
        var loan: LoanPageData? = nil
        if account.type == "LOAN" {
            loan = made.loanPage(
                accountId: target.id, hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone,
                monthsBack: Int32(given.pageBack), allRows: given.loanAll
            )
        }
        var invest: InvestPageData? = nil
        if account.type == "INVESTMENT" {
            invest = made.investPage(accountId: target.id, hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone)
        }
        return PagesData(account: account, card: card, person: nil, loan: loan, invest: invest)
    }

    // MARK: Pages

    func openAccountPage(_ id: Int64) {
        showPage(PageTarget(isPerson: false, id: id))
    }

    func openPersonPage(_ id: Int64) {
        showPage(PageTarget(isPerson: true, id: id))
    }

    private func showPage(_ target: PageTarget) {
        openPage = target
        pageBack = 0
        accountPage = nil
        cardPage = nil
        personPage = nil
        loanPage = nil
        investPage = nil
        loanAll = false
        refreshFigures()
    }

    func closePage() {
        openPage = nil
        pageBack = 0
        accountPage = nil
        cardPage = nil
        personPage = nil
        loanPage = nil
        investPage = nil
        loanAll = false
    }

    /// Moves the open page: -1 is one month (account) or statement (card) back, +1 forward. Never past what exists.
    func shiftPage(_ direction: Int) {
        var canBack = false
        if let loan = loanPage {
            canBack = loan.hasEarlier
        } else if let card = cardPage {
            canBack = card.canBack
        } else if let account = accountPage {
            canBack = account.hasEarlier
        }
        if direction < 0 {
            if !canBack { return }
            pageBack += 1
        } else {
            if pageBack == 0 { return }
            pageBack -= 1
        }
        refreshFigures()
    }

    // MARK: Sheets

    /// Opens the pay-card sheet for card [cardId] (only an open card with statement days has one).
    func openPaySheet(cardId: Int64) {
        let given = inputs()
        queue.async {
            guard let made = self.view else { return }
            let data: CardPayData = made.cardPayData(
                cardId: cardId, hidden: given.hidden, todayEpochDay: Int32(given.today), timeZoneId: given.zone
            )
            if !data.found { return }
            self.publish { self.paySheet = PaySheetRequest(data: data) }
        }
    }

    func closePaySheet() {
        paySheet = nil
    }

    /// Opens the sheet for a new payment with the person whose page is [page] open.
    func openNewPersonPayment(_ page: PersonPageData) {
        personSheet = PersonSheetRequest(
            personId: page.id, personName: page.name, defaultKind: page.defaultKind, suggestedCents: page.suggestedCents,
            editing: nil, amountText: ""
        )
    }

    func closePersonSheet() {
        personSheet = nil
    }
}
