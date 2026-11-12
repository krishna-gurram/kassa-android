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
                .kassa(.title)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            EyeButton(hidden: hidden, onToggle: onToggle)
        }
        .padding(.top, 8)
    }
}

/// Hide or show every amount. 48 pt, and says what tapping does.
struct EyeButton: View {
    let hidden: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Image(systemName: hidden ? "eye.slash" : "eye")
                .font(.system(size: 22))
                .foregroundColor(Palette.ink)
                .frame(minWidth: 48, minHeight: 48)
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
            .frame(height: 1)
    }
}

/// Primary actions are an ink pill (56 pt tall) with onInk text; the secondary one is a quiet chip pill.
struct InkButtonStyle: ButtonStyle {
    let filled: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Font.kassa(16, .semibold))
            .foregroundColor(filled ? Palette.onInk : Palette.ink)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Capsule().fill(filled ? Palette.ink : Palette.chip))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// A tab that is not built yet. No fake data.
struct ComingSoonScreen: View {
    let title: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .kassa(.title)
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

/// A big numeral (the entry amount, a payment, a share) whose size follows the Dynamic Type setting up to 130%, like Android's
/// `scaledSp`: the box around a numeral can't grow, so it scales a little and then stays. `.system(size:)` alone ignores the setting.
struct ScaledNumeral: ViewModifier {
    let size: CGFloat
    let weight: Font.Weight

    /// 100 points at the current setting: the ratio to 100 is how much larger text is now.
    @ScaledMetric(relativeTo: .largeTitle) private var hundred: CGFloat = 100

    /// The most a numeral grows.
    static let cap: CGFloat = 1.3

    /// The multiplier for a raw ratio: never below 1, never above the cap.
    static func factor(_ ratio: CGFloat) -> CGFloat {
        return min(max(ratio, 1), cap)
    }

    func body(content: Content) -> some View {
        let grown: CGFloat = size * ScaledNumeral.factor(hundred / 100)
        return content.font(Font.kassaFixed(grown, weight).monospacedDigit())
    }
}

extension View {
    func scaledNumeral(_ size: CGFloat, weight: Font.Weight = .bold) -> some View {
        return modifier(ScaledNumeral(size: size, weight: weight))
    }
}
