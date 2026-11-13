import XCTest
import SwiftUI
@testable import Kassa

/// Batch C: the width thresholds are Android's (600 two columns, 720 list-detail) and only the regular size class gets them.
final class LayoutTests: XCTestCase {

    func testThresholdsAreAndroids() {
        XCTAssertEqual(LayoutMode.twoColumnMin, 600)
        XCTAssertEqual(LayoutMode.listDetailMin, 720)
    }

    func testCompactBelow600() {
        XCTAssertEqual(LayoutMode.of(width: 0, regular: true), .compact)
        XCTAssertEqual(LayoutMode.of(width: 390, regular: true), .compact)
        XCTAssertEqual(LayoutMode.of(width: 599.5, regular: true), .compact)
    }

    func testTwoColumnFrom600Below720() {
        XCTAssertEqual(LayoutMode.of(width: 600, regular: true), .twoColumn)
        XCTAssertEqual(LayoutMode.of(width: 719.5, regular: true), .twoColumn)
    }

    func testListDetailFrom720() {
        XCTAssertEqual(LayoutMode.of(width: 720, regular: true), .listDetail)
        XCTAssertEqual(LayoutMode.of(width: 1024, regular: true), .listDetail)
    }

    func testCompactSizeClassIsAlwaysCompact() {
        // A narrow Split View or Slide Over window, or an iPhone, whatever the width says.
        XCTAssertEqual(LayoutMode.of(width: 1024, regular: false), .compact)
        XCTAssertEqual(LayoutMode.of(width: 700, regular: false), .compact)
    }

    func testWideAndCaps() {
        XCTAssertFalse(LayoutMode.compact.isWide)
        XCTAssertTrue(LayoutMode.twoColumn.isWide)
        XCTAssertTrue(LayoutMode.listDetail.isWide)
        XCTAssertEqual(LayoutMode.compact.contentMaxWidth, 720)
        XCTAssertEqual(LayoutMode.twoColumn.contentMaxWidth, LayoutMode.wideWidth)
    }

    func testListPaneIsCompactAndRailFits() {
        XCTAssertEqual(LayoutMode.of(width: LayoutMode.listPaneWidth, regular: true), .compact)
        XCTAssertGreaterThanOrEqual(LayoutMode.railWidth, 48)
    }

    func testShellLayout() {
        // iPhone: bottom bar.
        XCTAssertEqual(ShellLayout.of(width: 390, regular: false), ShellLayout(rail: false, content: .compact))
        // 1024 x 768 landscape and 820 x 1180 portrait iPads: rail, and list-detail beside it.
        XCTAssertEqual(ShellLayout.of(width: 1024, regular: true), ShellLayout(rail: true, content: .listDetail))
        XCTAssertEqual(ShellLayout.of(width: 820, regular: true), ShellLayout(rail: true, content: .listDetail))
        // A two-thirds Split View (about 678): the rail, but not enough room beside it for two columns.
        XCTAssertEqual(ShellLayout.of(width: 678, regular: true), ShellLayout(rail: true, content: .compact))
        // A half Split View (about 507 on a 1024 iPad, compact class): the phone layout.
        XCTAssertEqual(ShellLayout.of(width: 507, regular: false), ShellLayout(rail: false, content: .compact))
        // The thresholds apply to the width beside the rail: 700 leaves 612 (two columns), 808 leaves 720 (list-detail).
        XCTAssertEqual(ShellLayout.of(width: 700, regular: true), ShellLayout(rail: true, content: .twoColumn))
        XCTAssertEqual(ShellLayout.of(width: 808, regular: true).content, .listDetail)
        XCTAssertEqual(ShellLayout.of(width: 807, regular: true).content, .twoColumn)
    }
}
