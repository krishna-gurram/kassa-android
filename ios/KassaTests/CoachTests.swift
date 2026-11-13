import XCTest
import KassaShared
@testable import Kassa

/// Coach on iOS: the phone's choices (switches, the dismissed week) and the mapping from the shared `CoachData` to cards and words.
/// The figures themselves are :domain's (JVM `Coach*Test`, `CoachViewTest`); these check what the screens make of them.
final class CoachTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-coach-" + UUID().uuidString, isDirectory: true)
        KassaCore.shared.installDefaults()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func defaults() -> UserDefaults {
        let suite = "kassa-coach-tests-" + UUID().uuidString
        let made = UserDefaults(suiteName: suite) ?? UserDefaults.standard
        made.removePersistentDomain(forName: suite)
        return made
    }

    private func demoView() throws -> LedgerView {
        return try DemoFixture.view(directory: directory, keys: MemoryKeyStore())
    }

    // MARK: Prefs

    func testSwitchesStartOnAndAreRemembered() {
        let store = defaults()
        let prefs = CoachPrefs(defaults: store)
        XCTAssertTrue(prefs.weeklyCheckIn)
        XCTAssertTrue(prefs.insightCards)
        prefs.weeklyCheckIn = false
        prefs.insightCards = false
        XCTAssertFalse(CoachPrefs(defaults: store).weeklyCheckIn)
        XCTAssertFalse(CoachPrefs(defaults: store).insightCards)
        prefs.weeklyCheckIn = true
        XCTAssertTrue(CoachPrefs(defaults: store).weeklyCheckIn)
    }

    func testDismissalIsPerWeek() {
        let prefs = CoachPrefs(defaults: defaults())
        XCTAssertFalse(prefs.isDismissed(weekStart: 20750))
        prefs.dismissedWeek = 20750
        XCTAssertTrue(prefs.isDismissed(weekStart: 20750))
        XCTAssertFalse(prefs.isDismissed(weekStart: 20757))
        XCTAssertFalse(prefs.isDismissed(weekStart: 0))
    }

    func testModelRemembersDismissalAndSwitches() {
        let store = defaults()
        let model = AppModel(defaults: store)
        XCTAssertTrue(model.coachWeekly)
        model.setCoachWeekly(false)
        model.setCoachCards(false)
        model.dismissCheckIn(weekStart: 20750)
        let again = AppModel(defaults: store)
        XCTAssertFalse(again.coachWeekly)
        XCTAssertFalse(again.coachCards)
        XCTAssertEqual(again.coachDismissed, 20750)
    }

    // MARK: Mapping

    func testHiddenShowsNothingAndInfersNothing() throws {
        let view = try demoView()
        let day = DemoFixture.lastOfOctober
        let hidden = view.coach(hidden: true, todayEpochDay: day, timeZoneId: "UTC", firstWeekday: 2)
        XCTAssertTrue(CoachWording.cards(hidden, enabled: true).isEmpty)
        XCTAssertNil(CoachWording.teaser(hidden, weeklyOn: true))
        XCTAssertFalse(CoachWording.checkInReady(hidden, weeklyOn: true))
        let text = CoachWording.checkIn(hidden.checkIn, dismissedWeek: 0)
        XCTAssertEqual(text.state, "HIDDEN")
        XCTAssertNil(text.win)
        XCTAssertNil(text.watch)
        XCTAssertNil(text.decision)
    }

    func testCardsOnlyForRowsThatExist() throws {
        let view = try demoView()
        let data = view.coach(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2)
        let cards = CoachWording.cards(data, enabled: true)
        var flagged: Int = 0
        for row in data.pace.rows {
            if row.aboveLast || row.overBudget || row.paceOverBudget { flagged += 1 }
        }
        var paceCards: Int = 0
        var subCards: Int = 0
        var fundCards: Int = 0
        for card in cards {
            if card.id.hasPrefix("pace-") { paceCards += 1 }
            if card.id == "subscription" { subCards += 1 }
            if card.id == "fund" {
                fundCards += 1
                // The Accounts tab lists credit cards and their due lines first, so the fund card goes nowhere.
                XCTAssertNil(card.target)
            }
        }
        XCTAssertEqual(paceCards, min(2, flagged))
        XCTAssertEqual(subCards, data.subscriptions.rows.isEmpty ? 0 : 1)
        XCTAssertEqual(fundCards, data.fund.state == "READY" ? 1 : 0)
        XCTAssertTrue(CoachWording.cards(data, enabled: false).isEmpty)
    }

    func testTeaserFollowsTheCheckIn() throws {
        let view = try demoView()
        let data = view.coach(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2)
        let ready = data.checkIn.state == "READY"
        let something = !data.checkIn.watches.isEmpty || !data.checkIn.wins.isEmpty
        let line = CoachWording.teaser(data, weeklyOn: true)
        XCTAssertEqual(line != nil, ready && something)
        XCTAssertNil(CoachWording.teaser(data, weeklyOn: false))
        XCTAssertNil(CoachWording.teaser(nil, weeklyOn: true))
    }

    func testDecisionStaysOutForADismissedWeek() throws {
        let view = try demoView()
        for day in [DemoFixture.lastOfOctober, DemoFixture.lastOfOctober - 7, DemoFixture.lastOfOctober - 14] {
            let data = view.coach(hidden: false, todayEpochDay: day, timeZoneId: "UTC", firstWeekday: 2)
            let shown = CoachWording.checkIn(data.checkIn, dismissedWeek: 0)
            XCTAssertEqual(shown.decision != nil, !data.checkIn.decisions.isEmpty && data.checkIn.state == "READY")
            let set = CoachWording.checkIn(data.checkIn, dismissedWeek: Int(data.checkIn.weekStartDay))
            XCTAssertNil(set.decision)
            let later = CoachWording.checkIn(data.checkIn, dismissedWeek: Int(data.checkIn.weekStartDay) - 7)
            XCTAssertEqual(later.decision != nil, shown.decision != nil)
        }
    }

    func testMonthsText() {
        XCTAssertEqual(CoachWording.monthsText(21), "2.1")
        XCTAssertEqual(CoachWording.monthsText(5), "0.5")
    }

    func testPrivacyRowsAreTheSharedFacts() throws {
        let view = try demoView()
        let data = view.coach(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2)
        let keys = data.facts.map { $0.key }
        XCTAssertFalse(keys.isEmpty)
        for key in keys {
            XCTAssertEqual(key, key.lowercased())
        }
        let masked = view.coach(hidden: true, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2)
        XCTAssertEqual(masked.facts.map { $0.key }, keys)
    }
}
