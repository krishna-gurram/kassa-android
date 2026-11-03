import SwiftUI
import KassaShared

/// The top of a tab: its title and the eye (Hide balances). The eye sits in the top bar of every screen that shows amounts.
struct TabTop: View {
    let title: LocalizedStringKey
    let hidden: Bool
    let onToggle: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.largeTitle.weight(.bold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Spacer(minLength: 8)
            EyeButton(hidden: hidden, onToggle: onToggle)
        }
        .padding(.top, 8)
    }
}

/// Hide or show every amount. At least 44 pt, and says what tapping does.
struct EyeButton: View {
    let hidden: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Image(systemName: hidden ? "eye.slash" : "eye")
                .font(.system(size: 20))
                .foregroundColor(Palette.ink2)
                .frame(minWidth: 44, minHeight: 44)
        }
        .accessibilityLabel(hidden ? Text("a11y.showBalances") : Text("a11y.hideBalances"))
        .accessibilityIdentifier("hideBalances")
    }
}

/// A hairline between rows.
struct Hairline: View {
    var body: some View {
        Rectangle()
            .fill(Palette.line)
            .frame(height: 0.5)
    }
}

/// Primary actions are ink on the ground; the secondary one is a quiet chip.
struct InkButtonStyle: ButtonStyle {
    let filled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundColor(filled ? Palette.ground : Palette.ink)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(RoundedRectangle(cornerRadius: 14).fill(filled ? Palette.ink : Palette.chip))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// A tab that is not built yet. No fake data.
struct ComingSoonScreen: View {
    let title: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.largeTitle.weight(.bold))
                .padding(.top, 8)
            Spacer()
            Text("comingSoon")
                .font(.body)
                .foregroundColor(Palette.ink2)
                .frame(maxWidth: .infinity)
            Spacer()
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Palette.ground.ignoresSafeArea())
    }
}
