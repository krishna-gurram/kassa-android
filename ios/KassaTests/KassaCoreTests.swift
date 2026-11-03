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
}
