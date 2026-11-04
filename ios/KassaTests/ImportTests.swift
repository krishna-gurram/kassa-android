import XCTest
import KassaShared
@testable import Kassa

/// Tiny synthetic Money Manager and Cashew backup files, made at run time with plain SQLite. Nothing here is anyone's data.
enum ImportFixtures {

    static func tempFile(_ name: String) -> URL {
        return FileManager.default.temporaryDirectory.appendingPathComponent("kassa-fixture-" + UUID().uuidString + "-" + name)
    }

    /// Money Manager, Android layout: Checking 1000.00 and Savings 500 in a Bank group, a Card (statement day 5, due day 25),
    /// an expense of 12.50, income of 2000 and a transfer of 100 from Checking to the card. Main currency EUR.
    /// Checking ends at 2887.50 and Savings at 500.
    static func moneyManager() throws -> URL {
        let url = tempFile("fixture.mmbak")
        let db = try SQLiteDB(plainPath: url.path)
        defer { db.close() }
        let sql: [String] = [
            "CREATE TABLE ASSETGROUP (uid TEXT, ACC_GROUP_NAME TEXT, TYPE INTEGER, ORDERSEQ INTEGER, IS_DEL INTEGER)",
            "CREATE TABLE ASSETS (uid TEXT, NIC_NAME TEXT, groupUid TEXT, ORDERSEQ INTEGER, CARD_DAY_FIN INTEGER, CARD_DAY_PAY INTEGER, ZDATA TEXT, currencyUid TEXT, cardAssetUid TEXT)",
            "CREATE TABLE ZCATEGORY (uid TEXT, NAME TEXT, TYPE INTEGER, pUid TEXT, C_IS_DEL INTEGER, ORDERSEQ INTEGER)",
            "CREATE TABLE INOUTCOME (uid TEXT, DO_TYPE INTEGER, assetUid TEXT, toAssetUid TEXT, ctgUid TEXT, ZMONEY TEXT, ZDATE TEXT, ZCONTENT TEXT, txUidTrans TEXT, IS_DEL INTEGER)",
            "CREATE TABLE BUDGET (uid TEXT, targetUid TEXT, IS_TOTAL INTEGER, PERIOD_TYPE INTEGER, IS_DEL INTEGER)",
            "CREATE TABLE BUDGET_AMOUNT (budgetUid TEXT, BUDGET_PERIOD INTEGER, AMOUNT REAL, IS_DEL INTEGER)",
            "CREATE TABLE CURRENCY (uid TEXT, ISO TEXT, IS_MAIN_CURRENCY INTEGER, IS_DEL INTEGER)",
            "INSERT INTO ASSETGROUP VALUES ('g-bank', 'Bank', 1, 0, 0), ('g-card', 'Cards', 2, 1, 0)",
            "INSERT INTO ASSETS VALUES ('a-check', 'Checking', 'g-bank', 0, NULL, NULL, '1000.00', NULL, NULL)",
            "INSERT INTO ASSETS VALUES ('a-save', 'Savings', 'g-bank', 1, NULL, NULL, '500', NULL, NULL)",
            "INSERT INTO ASSETS VALUES ('a-card', 'Card', 'g-card', 2, 5, 25, '0', NULL, 'a-check')",
            "INSERT INTO ZCATEGORY VALUES ('c-food', 'Food', 1, NULL, 0, 0), ('c-pay', 'Salary', 0, NULL, 0, 1)",
            "INSERT INTO INOUTCOME VALUES ('t1', 1, 'a-check', NULL, 'c-food', '12.50', '1760000000000', 'Lunch', NULL, 0)",
            "INSERT INTO INOUTCOME VALUES ('t2', 0, 'a-check', NULL, 'c-pay', '2000', '1760100000000', 'Pay', NULL, 0)",
            "INSERT INTO INOUTCOME VALUES ('t3', 3, 'a-check', 'a-card', NULL, '100', '1760200000000', 'Card payment', 'p1', 0)",
            "INSERT INTO INOUTCOME VALUES ('t4', 4, 'a-card', 'a-check', NULL, '100', '1760200000000', 'Card payment', 'p1', 0)",
            "INSERT INTO BUDGET VALUES ('b1', 'c-food', 0, 0, 0)",
            "INSERT INTO BUDGET_AMOUNT VALUES ('b1', 0, 300.0, 0)",
            "INSERT INTO CURRENCY VALUES ('cur1', 'EUR', 1, 0)",
        ]
        for line in sql { try db.exec(line) }
        return url
    }

    /// Cashew: Everyday (usd) with an expense of 12.50 and income of 2000, Euro stash (eur) with an expense of 4. Dates are Cashew's ISO text.
    static func cashew() throws -> URL {
        let url = tempFile("fixture.sql")
        let db = try SQLiteDB(plainPath: url.path)
        defer { db.close() }
        let sql: [String] = [
            "CREATE TABLE wallets (wallet_pk TEXT, name TEXT, currency TEXT, \"order\" INTEGER)",
            "CREATE TABLE categories (category_pk TEXT, name TEXT, income INTEGER, \"order\" INTEGER, main_category_pk TEXT)",
            "CREATE TABLE transactions (transaction_pk TEXT, name TEXT, amount REAL, category_fk TEXT, wallet_fk TEXT, date_created TEXT, paid INTEGER, note TEXT)",
            "INSERT INTO wallets VALUES ('w1', 'Everyday', 'usd', 0), ('w2', 'Euro stash', 'eur', 1)",
            "INSERT INTO categories VALUES ('0', 'Balance Correction', 0, 0, NULL), ('food', 'Food', 0, 1, NULL), ('pay', 'Salary', 1, 2, NULL)",
            "INSERT INTO transactions VALUES ('t1', 'Lunch', -12.5, 'food', 'w1', '2025-10-10 10:00:00.000', 1, '')",
            "INSERT INTO transactions VALUES ('t2', 'Pay', 2000.0, 'pay', 'w1', '2025-10-11 10:00:00.000', 1, '')",
            "INSERT INTO transactions VALUES ('t3', 'Cafe', -4.0, 'food', 'w2', '2025-10-12 10:00:00.000', 1, '')",
        ]
        for line in sql { try db.exec(line) }
        return url
    }

    static func text(_ contents: String, _ name: String) throws -> URL {
        let url = tempFile(name)
        try contents.write(to: url, atomically: true, encoding: String.Encoding.utf8)
        return url
    }

    static func bytes(_ data: Data, _ name: String) throws -> URL {
        let url = tempFile(name)
        try data.write(to: url)
        return url
    }
}

enum FixtureError: Error {
    case notASession(String)
}

/// Importing from other apps: what a picked file is, the questions, the plan and its balance checks, the save over existing data
/// in one transaction, and the model that runs the screens.
final class ImportTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!
    private var made: [URL] = []

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-import-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        made = []
        KassaCore.shared.installDefaults()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        for url in made { try? FileManager.default.removeItem(at: url) }
    }

    // MARK: Helpers

    private func keep(_ url: URL) -> URL {
        made.append(url)
        return url
    }

    private func waitFor(_ condition: @escaping () -> Bool, timeout: TimeInterval = 60) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        return condition()
    }

    private func makeStore() throws -> LedgerStore {
        return try LedgerStore(directory: directory, keyStore: keys)
    }

    private func makeModel() -> AppModel {
        let folder = directory
        let store = keys
        let name = "kassa-import-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: name) ?? UserDefaults.standard
        defaults.removePersistentDomain(forName: name)
        return AppModel(defaults: defaults, openStore: { try LedgerStore(directory: folder!, keyStore: store!) })
    }

    private func session(_ read: ImportRead) throws -> ImportSession {
        switch read {
        case .session(let opened): return opened
        case .kassa: throw FixtureError.notASession("a Kassa file")
        case .refused(let text): throw FixtureError.notASession(text)
        }
    }

    private func refusal(_ read: ImportRead) -> String? {
        if case .refused(let text) = read { return text }
        return nil
    }

    private func openMoneyManager() throws -> ImportSession {
        let file = keep(try ImportFixtures.moneyManager())
        return try session(try ImportReader.read(copy: file, currency: "GBP", zone: "UTC"))
    }

    private func openCashew() throws -> ImportSession {
        let file = keep(try ImportFixtures.cashew())
        return try session(try ImportReader.read(copy: file, currency: "GBP", zone: "UTC"))
    }

    /// Every account's balance worked out again from the saved rows, by name.
    private func balances(_ data: BackupData) -> [String: Int64] {
        var byId: [Int64: Int64] = [:]
        var names: [Int64: String] = [:]
        for account in data.accounts {
            byId[account.id] = account.openingBalance
            names[account.id] = account.name
        }
        for entry in data.transactions {
            let from: Int64 = entry.accountId?.int64Value ?? 0
            let to: Int64 = entry.toAccountId?.int64Value ?? 0
            if entry.kind == "EXPENSE" {
                byId[from] = (byId[from] ?? 0) - entry.amount
            } else if entry.kind == "INCOME" {
                byId[from] = (byId[from] ?? 0) + entry.amount
            } else if entry.kind == "TRANSFER" {
                byId[from] = (byId[from] ?? 0) - entry.amount
                byId[to] = (byId[to] ?? 0) + entry.amount
            }
        }
        var result: [String: Int64] = [:]
        for (id, name) in names { result[name] = byId[id] ?? 0 }
        return result
    }

    // MARK: What a file is

    func testTextAndEmptyAndOtherFilesAreRefusedInPlainWords() throws {
        let text = keep(try ImportFixtures.text("hello, this is not a database at all", "notes.txt"))
        let empty = keep(try ImportFixtures.bytes(Data(), "empty.mmbak"))
        let pdf = keep(try ImportFixtures.bytes(Data("%PDF-1.7 not really".utf8), "doc.pdf"))
        for file in [text, empty, pdf] {
            let read = try ImportReader.read(copy: file, currency: "GBP", zone: "UTC")
            let words = refusal(read)
            XCTAssertNotNil(words, file.lastPathComponent)
            XCTAssertFalse((words ?? "").isEmpty)
            XCTAssertFalse((words ?? "").contains("SQLite"))
        }
    }

    func testADamagedSQLiteFileIsRefusedNotAnError() throws {
        var bytes = Data("SQLite format 3\u{0}".utf8)
        bytes.append(Data(repeating: 7, count: 300))
        let file = keep(try ImportFixtures.bytes(bytes, "broken.mmbak"))
        let read = try ImportReader.read(copy: file, currency: "GBP", zone: "UTC")
        XCTAssertNotNil(refusal(read))
    }

    func testADatabaseThatIsNeitherAppIsRefused() throws {
        let url = keep(ImportFixtures.tempFile("other.db"))
        let db = try SQLiteDB(plainPath: url.path)
        try db.exec("CREATE TABLE notes (id INTEGER, body TEXT)")
        db.close()
        let read = try ImportReader.read(copy: url, currency: "GBP", zone: "UTC")
        XCTAssertNotNil(refusal(read))
    }

    func testAKassaFileIsHandedOnToRestore() throws {
        let json = BackupBridge.shared.write(data: try GoldenFixture.data())
        let bytes = try BackupFile.make(json: json, password: nil)
        let file = keep(try ImportFixtures.bytes(bytes, "mine.kassa"))
        let read = try ImportReader.read(copy: file, currency: "GBP", zone: "UTC")
        if case .kassa = read { return }
        XCTFail("a .kassa file should go to restore")
    }

    // MARK: Money Manager

    func testMoneyManagerScanPlanAndBalancesEqualTheSource() throws {
        let opened = try openMoneyManager()
        XCTAssertEqual(opened.kind, "MONEY_MANAGER")
        XCTAssertEqual(opened.detectedCurrency(), "EUR")
        XCTAssertTrue(opened.hasReview())
        let review = try opened.review(hidden: false)
        XCTAssertEqual(review.accounts, 3)
        XCTAssertEqual(review.expenses, 1)
        XCTAssertEqual(review.income, 1)
        XCTAssertEqual(review.transfers, 1)
        XCTAssertTrue(review.allMatch)
        XCTAssertEqual(review.accountsBad, 0)
        XCTAssertEqual(review.accountChecks, 3)
    }

    func testMoneyManagerSavesInOneTransactionAndBalancesAreTheSources() throws {
        let store = try makeStore()
        let opened = try openMoneyManager()
        try store.replaceForImport(opened, currency: "EUR")
        let data = try store.export()
        XCTAssertEqual(data.currency, "EUR")
        XCTAssertEqual(data.transactions.count, 3)
        let sums = balances(data)
        XCTAssertEqual(sums["Checking"], 288750)
        XCTAssertEqual(sums["Savings"], 50000)
        // The transfer pair is one transfer, from Checking to the Card.
        let transfers = data.transactions.filter { $0.kind == "TRANSFER" }
        XCTAssertEqual(transfers.count, 1)
        XCTAssertEqual(transfers.first?.amount, 10000)
    }

    func testTheTemporaryCopyIsDeletedWhateverHappens() throws {
        let file = keep(try ImportFixtures.moneyManager())
        let before = try FileManager.default.contentsOfDirectory(atPath: FileManager.default.temporaryDirectory.path)
            .filter { $0.hasPrefix("kassa-import-") && $0.hasSuffix(".db") }
        let read = try ImportReader.read(file, currency: "GBP", zone: "UTC")
        XCTAssertNotNil(try? session(read))
        let notAFile = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-missing-" + UUID().uuidString)
        XCTAssertThrowsError(try ImportReader.read(notAFile, currency: "GBP", zone: "UTC"))
        let after = try FileManager.default.contentsOfDirectory(atPath: FileManager.default.temporaryDirectory.path)
            .filter { $0.hasPrefix("kassa-import-") && $0.hasSuffix(".db") }
        XCTAssertEqual(before.count, after.count)
        // The picked file itself is never changed or removed.
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    }

    // MARK: Over existing data

    func testImportReplacesAccountsAndEntriesAndForgetsTheOldArrangement() throws {
        let store = try makeStore()
        try store.replace(with: try GoldenFixture.data())
        let before = try store.export()
        XCTAssertFalse(before.accounts.isEmpty)
        let opened = try openMoneyManager()
        try store.replaceForImport(opened, currency: "EUR")
        let after = try store.export()
        XCTAssertEqual(Set(after.accounts.map { $0.name }), Set(["Checking", "Savings", "Card"]))
        XCTAssertEqual(after.transactions.count, 3)
        XCTAssertTrue(after.holdings.isEmpty)
        XCTAssertTrue(after.bills.isEmpty)
        XCTAssertTrue(after.loanTerms.isEmpty)
        XCTAssertNil(after.accountArrangement)
        XCTAssertEqual(after.monthStartDay, before.monthStartDay)
    }

    func testAFailureAfterTheInsertsRollsEverythingBack() throws {
        let store = try makeStore()
        try store.replace(with: try GoldenFixture.data())
        let before = BackupBridge.shared.digest(data: try store.export())
        store.afterInsertHook = { throw LedgerStoreError.badValue }
        let opened = try openMoneyManager()
        XCTAssertThrowsError(try store.replaceForImport(opened, currency: "EUR"))
        store.afterInsertHook = nil
        let after = BackupBridge.shared.digest(data: try store.export())
        XCTAssertEqual(before, after)
    }

    // MARK: Cashew

    func testCashewAsksItsQuestionsThenSavesWithBalancesFromTheSource() throws {
        let opened = try openCashew()
        XCTAssertEqual(opened.kind, "CASHEW")
        XCTAssertFalse(opened.hasReview())
        XCTAssertEqual(try opened.question(hidden: false).step, "FOUND")
        try opened.goOn()
        let accounts = try opened.question(hidden: false)
        XCTAssertEqual(accounts.step, "ACCOUNTS")
        XCTAssertFalse(accounts.canGoOn)
        opened.setAccountType(pk: "w1", type: "CHECKING")
        opened.setAccountType(pk: "w2", type: "SAVINGS")
        XCTAssertTrue(try opened.question(hidden: false).canGoOn)
        try opened.goOn()
        XCTAssertEqual(try opened.question(hidden: false).step, "CURRENCY")
        opened.setLeaveOut(pk: "w2", leaveOut: true)
        try opened.goOn()
        XCTAssertTrue(opened.hasReview())
        let review = try opened.review(hidden: false)
        XCTAssertTrue(review.allMatch)
        XCTAssertEqual(review.entries, 2)
        let store = try makeStore()
        try store.replaceForImport(opened, currency: "USD")
        let data = try store.export()
        XCTAssertEqual(data.transactions.count, 2)
        XCTAssertEqual(balances(data)["Everyday"], 198750)
    }

    // MARK: The model

    func testTheFirstRunFlowReadsReviewsAndSaves() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        let file = keep(try ImportFixtures.moneyManager())
        model.importer.begin(file, replacing: false)
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.review })
        XCTAssertEqual(model.importer.review?.accounts, 3)
        model.importer.confirm()
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.done })
        XCTAssertEqual(model.importer.doneReview?.entries, 3)
        XCTAssertTrue(waitFor { model.isReady })
        model.importer.reset()
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.idle })
    }

    func testFromSettingsItAsksBeforeReplacing() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(try DemoFixture.url())
        XCTAssertTrue(waitFor { model.isReady && model.overview != nil })
        let file = keep(try ImportFixtures.moneyManager())
        model.importer.begin(file, replacing: true)
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.review })
        model.importer.confirm()
        XCTAssertTrue(model.importer.asksReplace)
        XCTAssertEqual(model.importer.phase, ImportPhase.review)
        model.importer.save(pickedCurrency: nil)
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.done })
    }

    func testARefusedFileShowsAPlainSentence() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        let file = keep(try ImportFixtures.text("just some words", "words.txt"))
        model.importer.begin(file, replacing: false)
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.failed })
        let words = model.importer.message ?? ""
        XCTAssertFalse(words.isEmpty)
        XCTAssertFalse(words.contains("Exception"))
    }

    // MARK: Opened from outside (Files, share sheet)

    func testAFileOpenedOnFirstRunGoesIntoTheImport() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.receiveFile(keep(try ImportFixtures.moneyManager()))
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.review })
        XCTAssertFalse(model.importer.overExistingData)
        XCTAssertEqual(model.settingsRequest, 0)
    }

    func testAFileOpenedWithDataAsksBeforeReplacingAndShowsSettings() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        model.chooseBackup(try DemoFixture.url())
        XCTAssertTrue(waitFor { model.isReady && model.overview != nil })
        model.receiveFile(keep(try ImportFixtures.cashew()))
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.questions || model.importer.phase == ImportPhase.review })
        XCTAssertTrue(model.importer.overExistingData)
        XCTAssertEqual(model.settingsRequest, 1)
    }

    func testAFileOpenedWhileOpeningWaitsUntilTheAppIsReady() throws {
        let model = makeModel()
        model.receiveFile(keep(try ImportFixtures.moneyManager()))
        XCTAssertNotNil(model.pendingOpenURL)
        XCTAssertEqual(model.importer.phase, ImportPhase.idle)
        model.start()
        XCTAssertTrue(waitFor { model.canTakeFiles })
        model.drainPendingFile()
        XCTAssertNil(model.pendingOpenURL)
        XCTAssertTrue(waitFor { model.importer.phase == ImportPhase.review })
    }

    func testAnythingButAFileURLIsIgnored() throws {
        let model = makeModel()
        model.start()
        XCTAssertTrue(waitFor { model.isFirstRun })
        let web = try XCTUnwrap(URL(string: "kassa://open"))
        model.receiveFile(web)
        XCTAssertEqual(model.importer.phase, ImportPhase.idle)
        XCTAssertNil(model.pendingOpenURL)
    }
}
