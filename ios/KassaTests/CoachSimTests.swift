import XCTest
import KassaShared
@testable import Kassa

/// The Coach simulator on iOS: what the form builds, how the shared verdicts are worded and tinted, the cushion the phone remembers and the
/// what-ifs a person takes away. The figures themselves are :domain's (JVM `CoachSimTest`, `CoachSimViewTest`).
final class CoachSimTests: XCTestCase {

    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-sim-" + UUID().uuidString, isDirectory: true)
        KassaCore.shared.installDefaults()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func defaults() -> UserDefaults {
        let suite = "kassa-sim-tests-" + UUID().uuidString
        let made = UserDefaults(suiteName: suite) ?? UserDefaults.standard
        made.removePersistentDomain(forName: suite)
        return made
    }

    private func demoView() throws -> LedgerView {
        return try DemoFixture.view(directory: directory, keys: MemoryKeyStore())
    }

    private func sim(_ view: LedgerView, hidden: Bool = false, text: String = "", cushion: Int64 = 0) -> CoachSimData {
        return view.coachSim(
            hidden: hidden, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: "UTC", firstWeekday: 2, whatIfs: text, cushionCents: cushion
        )
    }

    // MARK: Chips build what-if text through the bridge

    func testChipsBuildLinesTheBridgeUnderstands() {
        let day = Int(DemoFixture.lastOfOctober) + 3
        let chips: [SimChip] = [
            SimChips.spend(cents: 90_000, day: day, shown: "$900.00"),
            SimChips.delay(key: "card-1", name: "Travel", toDay: day + 2),
            SimChips.extra(cents: 1_500, fromDay: day, shown: "$15.00"),
            SimChips.payday(toDay: day + 5),
        ]
        XCTAssertEqual(CoachSimBridge.shared.countWhatIfs(text: SimChips.text(chips)), 4)
        XCTAssertEqual(CoachSimBridge.shared.countWhatIfs(text: SimChips.text([])), 0)
        XCTAssertEqual(Set(chips.map { $0.id }).count, 4)
        XCTAssertEqual(chips[0].kind, SimChip.Kind.spend)
        XCTAssertEqual(chips[0].amountCents, 90_000)
    }

    func testDraftRoundTripsAndRefusesJunk() {
        let day = Int(DemoFixture.lastOfOctober) + 4
        var draft = SimDraft()
        XCTAssertTrue(draft.isEmpty)
        draft.typed = "900.5"
        draft.day = day
        draft.extras = [SimChips.delay(key: "bill-7", name: "Rent | Flat", toDay: day + 1), SimChips.payday(toDay: day + 2)]
        let back = SimDraft.decode(draft.encoded, maxExtras: 7)
        XCTAssertEqual(back, draft)
        XCTAssertTrue(SimDraft.decode("nonsense", maxExtras: 7).isEmpty)
        XCTAssertTrue(SimDraft.decode("", maxExtras: 7).isEmpty)
        XCTAssertEqual(SimDraft.decode(draft.encoded, maxExtras: 1).extras.count, 1)
    }

    func testDayStaysInsideTheHorizon() {
        var draft = SimDraft()
        XCTAssertEqual(draft.resolvedDay(today: 100, horizon: 30), 100)
        draft.day = 90
        XCTAssertEqual(draft.resolvedDay(today: 100, horizon: 30), 100)
        draft.day = 500
        XCTAssertEqual(draft.resolvedDay(today: 100, horizon: 30), 130)
        draft.day = 110
        XCTAssertEqual(draft.resolvedDay(today: 100, horizon: 30), 110)
    }

    // MARK: Verdicts

    func testVerdictToTone() {
        XCTAssertEqual(CoachSimWording.tone("ABOVE_CUSHION"), SimTone.green)
        XCTAssertEqual(CoachSimWording.tone("TIGHT"), SimTone.orange)
        XCTAssertEqual(CoachSimWording.tone("SHORT"), SimTone.orange)
        for grey in ["NOT_ENOUGH_DATA", "NO_ACCOUNTS", "HIDDEN", "PROBLEM", "anything else"] {
            XCTAssertEqual(CoachSimWording.tone(grey), SimTone.grey, grey)
        }
        XCTAssertTrue(CoachSimWording.hasMonth("TIGHT"))
        XCTAssertFalse(CoachSimWording.hasMonth("HIDDEN"))
    }

    func testEveryProblemCodeHasPlainWording() {
        for code in CoachSimWording.problems {
            let text = CoachSimWording.problemText(code)
            XCTAssertFalse(text.isEmpty, code)
            XCTAssertFalse(text.hasPrefix("coachsim."), "missing wording for \(code)")
        }
        XCTAssertFalse(CoachSimWording.problemText("SOMETHING_NEW").hasPrefix("coachsim."))
    }

    func testEveryKindHasAWordAndASymbol() {
        for kind in ["PAYCHECK", "BILL", "CARD_PAYMENT", "LOAN_PAYMENT", "RECURRING", "USUAL_SPENDING", "SPEND", "EXTRA_MONTHLY"] {
            XCTAssertFalse(CoachSimWording.kindLabel(kind).hasPrefix("coachsim."), kind)
            XCTAssertNotEqual(CoachSimWording.symbol(kind), "circle", kind)
        }
    }

    func testBadTextIsAProblemWithItsSentence() throws {
        let data = sim(try demoView(), text: "nonsense")
        XCTAssertEqual(data.verdict, "PROBLEM")
        XCTAssertEqual(data.problem, "BAD_ENCODING")
        let hero = CoachSimWording.hero(data, cushionCents: 0)
        XCTAssertEqual(hero.sub, CoachSimWording.problemText("BAD_ENCODING"))
        XCTAssertEqual(hero.tone, SimTone.grey)
    }

    func testPlainMonthIsAJudgedVerdictWithDaysInOrder() throws {
        let data = sim(try demoView())
        XCTAssertTrue(CoachSimWording.verdicts.contains(data.verdict), data.verdict)
        if !CoachSimWording.hasMonth(data.verdict) { return }
        XCTAssertFalse(data.chartDays.isEmpty)
        XCTAssertEqual(data.chartDays.count, data.chartBalances.count)
        let groups = CoachSimWording.dayGroups(data)
        XCTAssertEqual(groups.map { $0.day }, groups.map { $0.day }.sorted())
        XCTAssertTrue(CoachSimWording.whatMoves(data).allSatisfy { $0.kind != "USUAL_SPENDING" })
        XCTAssertFalse(CoachSimWording.chartSummary(data, symbol: "$").isEmpty)
        XCTAssertFalse(CoachSimWording.baselineDiffers(data))
    }

    func testBigPurchaseChangesTheMonth() throws {
        let view = try demoView()
        let day = Int(DemoFixture.lastOfOctober) + 2
        let chip = SimChips.spend(cents: 99_999_999, day: day, shown: "$999,999.99")
        let data = sim(view, text: SimChips.text([chip]))
        if !CoachSimWording.hasMonth(data.verdict) { return }
        XCTAssertEqual(data.verdict, "SHORT")
        XCTAssertTrue(CoachSimWording.baselineDiffers(data))
        XCTAssertEqual(CoachSimWording.hero(data, cushionCents: 0).tone, SimTone.orange)
    }

    // MARK: Hidden

    func testHiddenShowsOnlyTheMaskAndTheHiddenSentence() throws {
        let view = try demoView()
        let chip = SimChips.spend(cents: 90_000, day: Int(DemoFixture.lastOfOctober) + 1, shown: "$900.00")
        let data = sim(view, hidden: true, text: SimChips.text([chip]))
        XCTAssertEqual(data.verdict, "HIDDEN")
        XCTAssertTrue(data.chartDays.isEmpty)
        XCTAssertTrue(data.events.isEmpty)
        XCTAssertTrue(CoachSimWording.dayGroups(data).isEmpty)
        XCTAssertTrue(CoachSimWording.whatMoves(data).isEmpty)
        XCTAssertEqual(CoachSimWording.chartSummary(data, symbol: "$"), "")
        let hero = CoachSimWording.hero(data, cushionCents: 5_000)
        XCTAssertEqual(hero.tone, SimTone.grey)
        XCTAssertEqual(hero.label, String(localized: "coachsim.hero.hidden"))
        XCTAssertEqual(hero.figure, data.lowestText)
        XCTAssertEqual(hero.note, "")
    }

    // MARK: Cushion

    func testCushionStartsAtZeroAndIsRemembered() {
        let store = defaults()
        XCTAssertEqual(CoachPrefs(defaults: store).cushionCents, 0)
        CoachPrefs(defaults: store).cushionCents = 150_000
        XCTAssertEqual(CoachPrefs(defaults: store).cushionCents, 150_000)
        let model = AppModel(defaults: store)
        XCTAssertEqual(model.simCushion, 150_000)
        model.loadSimCushionText()
        XCTAssertFalse(model.simCushionText.isEmpty)
        model.setSimCushion("25.50")
        XCTAssertEqual(AppModel(defaults: store).simCushion, 2_550)
        model.setSimCushion("")
        XCTAssertEqual(AppModel(defaults: store).simCushion, 0)
    }

    func testCushionDecidesTheWordsOfAStayAboveAnswer() throws {
        let data = sim(try demoView())
        if data.verdict != "ABOVE_CUSHION" { return }
        XCTAssertEqual(CoachSimWording.hero(data, cushionCents: 0).label, String(localized: "coachsim.hero.aboveZero"))
        XCTAssertEqual(CoachSimWording.hero(data, cushionCents: 100).label, String(localized: "coachsim.hero.aboveCushion"))
    }

    // MARK: What-ifs taken away

    func testRemovingAWhatIfKeepsTheOthers() {
        let model = AppModel(defaults: defaults())
        let day = Int(DemoFixture.lastOfOctober) + 3
        let a = SimChips.spend(cents: 90_000, day: day, shown: "$900.00")
        let b = SimChips.payday(toDay: day + 4)
        model.simApplied = [a, b]
        XCTAssertEqual(model.simText(for: ReadRoute.coachMonth), SimChips.text([a, b]))
        model.removeSimApplied(a)
        XCTAssertEqual(model.simApplied, [b])
        model.clearSimApplied()
        XCTAssertTrue(model.simApplied.isEmpty)
        XCTAssertEqual(model.simText(for: ReadRoute.coachMonth), "")
        XCTAssertEqual(model.simText(for: ReadRoute.coachAsk), "")
        XCTAssertNil(model.simText(for: ReadRoute.coach))
        XCTAssertNil(model.simText(for: nil))
    }

    func testExtrasAreKeptToTheLimitAndNotRepeated() {
        let model = AppModel(defaults: defaults())
        let day = Int(DemoFixture.lastOfOctober) + 1
        let one = SimChips.payday(toDay: day)
        model.addSimExtra(one)
        model.addSimExtra(one)
        XCTAssertEqual(model.simDraft.extras.count, 1)
        for offset in 2..<20 {
            model.addSimExtra(SimChips.payday(toDay: day + offset))
        }
        XCTAssertLessThan(model.simDraft.extras.count, model.simMaxWhatIfs)
        model.removeSimExtra(one)
        XCTAssertFalse(model.simDraft.extras.contains(one))
    }
}
