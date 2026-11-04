import XCTest
import KassaShared

final class KassaCoreTests: XCTestCase {

    func testFrameworkLoadsAndHasAVersion() {
        XCTAssertFalse(KassaCore.shared.version().isEmpty)
    }

    func testFormatMoney() {
        KassaCore.shared.installDefaults()
        XCTAssertEqual(KassaCore.shared.formatMoney(cents: 123456, symbol: "$"), "$1,234.56")
    }

    func testInstallDefaultsTwiceChangesNothing() {
        KassaCore.shared.installDefaults()
        KassaCore.shared.installDefaults()
        XCTAssertEqual(KassaCore.shared.formatMoney(cents: 5, symbol: "£"), "£0.05")
    }

    func testLocaleIdentifierDecidesLakhGrouping() {
        KassaCore.shared.useLocale(identifier: "en_IN", decimal: ".", grouping: ",", zeroDigit: "0", minus: "-")
        XCTAssertEqual(KassaCore.shared.formatMoney(cents: 123456750, symbol: "R"), "R12,34,567.50")
        KassaCore.shared.useLocale(identifier: "de_DE", decimal: ",", grouping: ".", zeroDigit: "0", minus: "-")
        XCTAssertEqual(KassaCore.shared.formatMoney(cents: 123456750, symbol: "R"), "R1.234.567,50")
        KassaCore.shared.installDefaults()
        XCTAssertEqual(KassaCore.shared.formatMoney(cents: 123456750, symbol: "R"), "R1,234,567.50")
    }
}
