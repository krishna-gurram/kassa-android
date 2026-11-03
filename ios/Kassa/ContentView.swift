import SwiftUI
import KassaShared

struct ContentView: View {

    // An example figure, not anyone's money: 1,234.56 in minor units.
    private let sampleCents: Int64 = 123456

    var body: some View {
        VStack(spacing: 12) {
            Text("Kassa")
                .font(.largeTitle)
                .bold()
                .foregroundColor(.primary)
            Text(KassaCore.shared.formatMoney(cents: sampleCents, symbol: "$"))
                .font(.title)
                .foregroundColor(.primary)
            Text("Sample figure")
                .font(.footnote)
                .foregroundColor(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(UIColor.systemBackground))
    }
}
