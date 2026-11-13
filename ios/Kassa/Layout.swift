import SwiftUI

/// How wide a screen is laid out. The thresholds are Android's (CLAUDE.md, Navigation): 600 pt and up is two columns, 720 pt and up
/// shows a page beside the list it came from (list-detail). Both need the regular horizontal size class, so an iPhone in portrait, a
/// narrow Split View or Slide Over window stays the compact (phone) layout whatever its width.
/// This is layout only, so the numbers live here and not in :domain (no rule is shared with Android beyond the two widths).
enum LayoutMode: Equatable {
    case compact
    case twoColumn
    case listDetail

    /// 600: two columns (Overview, Accounts, Insights) and the leading rail instead of the bottom bar.
    static let twoColumnMin: CGFloat = 600
    /// 720: a card, account, loan, person, category, day or other page opens beside the list.
    static let listDetailMin: CGFloat = 720
    /// The leading tab rail's width.
    static let railWidth: CGFloat = 88
    /// The list's width while a page is open beside it. Narrower than 600, so the list pane draws the compact layout.
    static let listPaneWidth: CGFloat = 360
    /// The widest a single readable column gets (Settings, pages, Activity).
    static let readableWidth: CGFloat = 720
    /// The widest a two-column screen gets.
    static let wideWidth: CGFloat = 1200

    /// The mode for a pane [width] points wide. [regular] is `horizontalSizeClass == .regular`.
    static func of(width: CGFloat, regular: Bool) -> LayoutMode {
        if !regular { return .compact }
        if width >= listDetailMin { return .listDetail }
        if width >= twoColumnMin { return .twoColumn }
        return .compact
    }

    /// Two columns or more.
    var isWide: Bool {
        return self != .compact
    }

    /// The cap on a screen's content width: the phone's 720 while compact, wider when it lays out in columns.
    var contentMaxWidth: CGFloat {
        return isWide ? LayoutMode.wideWidth : LayoutMode.readableWidth
    }
}

/// What the app shell does at a window width: a leading rail (and no bottom bar, no floating Add) from 600, and the mode of the
/// area beside the rail, which decides whether a page opens beside its list.
struct ShellLayout: Equatable {
    let rail: Bool
    let content: LayoutMode

    static func of(width: CGFloat, regular: Bool) -> ShellLayout {
        let wide: Bool = LayoutMode.of(width: width, regular: regular).isWide
        if !wide { return ShellLayout(rail: false, content: .compact) }
        let beside: CGFloat = width - LayoutMode.railWidth
        return ShellLayout(rail: true, content: LayoutMode.of(width: beside, regular: regular))
    }
}

private struct LayoutModeKey: EnvironmentKey {
    static let defaultValue: LayoutMode = .compact
}

extension EnvironmentValues {
    /// The layout mode of the nearest `PaneLayout` (compact when there is none, so a view drawn alone is the phone layout).
    var layoutMode: LayoutMode {
        get { return self[LayoutModeKey.self] }
        set { self[LayoutModeKey.self] = newValue }
    }
}

/// Measures the space it is given and tells its content the mode for that width (and puts it in the environment). Each pane of a
/// list-detail screen is its own `PaneLayout`, so the list beside a page is compact even on a wide iPad.
struct PaneLayout<Content: View>: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    private let content: (LayoutMode) -> Content

    init(@ViewBuilder content: @escaping (LayoutMode) -> Content) {
        self.content = content
    }

    var body: some View {
        GeometryReader { proxy in
            let mode: LayoutMode = LayoutMode.of(width: proxy.size.width, regular: sizeClass == .regular)
            content(mode)
                .environment(\.layoutMode, mode)
                .frame(width: proxy.size.width, height: proxy.size.height)
        }
    }
}

/// A list with an optional page beside it. The list keeps its place in the view tree whether or not a page is open, so opening one
/// does not reset the list (scroll position, folds, typed search).
struct ListDetailSplit<ListPane: View, Detail: View>: View {
    let list: ListPane
    let detail: Detail?

    var body: some View {
        let listWidth: CGFloat = detail == nil ? CGFloat.infinity : LayoutMode.listPaneWidth
        return HStack(spacing: 0) {
            list
                .frame(maxWidth: listWidth)
            if let page = detail {
                Rectangle()
                    .fill(Palette.line)
                    .frame(width: 1)
                    .ignoresSafeArea(edges: .vertical)
                page
                    .frame(maxWidth: .infinity)
            }
        }
    }
}
