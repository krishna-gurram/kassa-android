import SwiftUI
import KassaShared

/// The Patterns tiles of Insights in one row (600 pt and up, as Android's unfolded Insights): each tile is the shared tile with its
/// mini bars, and opens the same detail page as a row of the phone list does. The tiles share the width equally and grow with the text.
struct PatternsRow: View {
    let tiles: [PatternTileData]
    let onOpen: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionLabel(title: "insights.patterns")
                .overlay(alignment: .bottom) { Hairline() }
            HStack(alignment: .top, spacing: 8) {
                ForEach(0..<tiles.count, id: \.self) { index in
                    Button(action: { onOpen(ReadWording.detailKind(forTile: tiles[index].kind)) }) {
                        PatternTileView(tile: tiles[index])
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 10)
        }
    }
}
