import SwiftUI
import KassaShared

struct ContentView: View {

    // An example figure, not anyone's money: 1,234.56 in minor units.
    private let sampleCents: Int64 = 123456

    @State private var storageStatus = ""

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
            Text(storageStatus)
                .font(.footnote)
                .foregroundColor(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(UIColor.systemBackground))
        .onAppear {
            if storageStatus.isEmpty {
                storageStatus = ContentView.openStorage()
            }
        }
    }

    /// Opens the encrypted database once and says whether it worked. Nothing else uses it yet. Failures are worded here;
    /// the system's own text never reaches the screen.
    private static func openStorage() -> String {
        do {
            _ = try LedgerStore.openDefault()
            return "Storage ready"
        } catch is KeyUnreadable {
            return "Storage can't be opened: its key is missing"
        } catch {
            return "Storage can't be opened"
        }
    }
}
