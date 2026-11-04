import SwiftUI
import KassaShared

/// Settings > Notifications: one rule per kind (a card payment, a loan payment, a bill: on or off, how many days before, at what time),
/// the daily reminder, and the state of the phone's permission. The defaults and the limits are :domain's (`DueBridge`); nothing here
/// pays or adds anything, a notification only tells.
struct NotificationSettingsPage: View {
    @ObservedObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    @State private var rules: [String: DueRuleValue] = [:]
    @State private var reminderOn = false
    @State private var reminderMinute = DueSettings.reminderDefaultMinute
    @State private var status: NoticeStatus = .notAsked

    private let kinds: [String] = DueSettings.kinds

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("notif.title")
                    .font(.largeTitle.weight(.bold))
                    .padding(.top, 16)
                statusBlock
                ForEach(kinds, id: \.self) { kind in
                    ruleBlock(kind)
                }
                reminderBlock
                Text("notif.footer")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .padding(.top, 8)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 96)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .onAppear { load() }
        .onChange(of: scenePhase) { phase in
            if phase == .active { loadStatus() }
        }
    }

    private func load() {
        let settings = DueSettings(defaults: model.defaults)
        var next: [String: DueRuleValue] = [:]
        for kind in kinds { next[kind] = settings.rule(kind) }
        rules = next
        reminderOn = settings.reminderOn
        reminderMinute = settings.reminderMinute
        loadStatus()
    }

    private func loadStatus() {
        model.noticeStatus { found in status = found }
    }

    // MARK: Permission

    @ViewBuilder
    private var statusBlock: some View {
        if status == .denied {
            VStack(alignment: .leading, spacing: 8) {
                Text("notif.off")
                    .font(.body.weight(.semibold))
                Text("notif.offDetail")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                Button("notif.openSettings") { openSystemSettings() }
                    .buttonStyle(InkButtonStyle(filled: false))
            }
            .padding(.vertical, 8)
            Hairline()
        } else if status == .notAsked {
            VStack(alignment: .leading, spacing: 8) {
                Text("notif.notAsked")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                Button("notif.allow") {
                    model.requestNoticePermission { found in status = found }
                }
                .buttonStyle(InkButtonStyle(filled: false))
            }
            .padding(.vertical, 8)
            Hairline()
        }
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    // MARK: Rules

    private func kindTitle(_ kind: String) -> LocalizedStringKey {
        switch kind {
        case "CARD": return "notif.kind.card"
        case "LOAN": return "notif.kind.loan"
        default: return "notif.kind.bill"
        }
    }

    private func current(_ kind: String) -> DueRuleValue {
        return rules[kind] ?? DueRuleValue(enabled: false, daysBefore: 0, minute: DueSettings.reminderDefaultMinute)
    }

    private func change(_ kind: String, _ edit: (inout DueRuleValue) -> Void) {
        var value = current(kind)
        edit(&value)
        rules[kind] = value
        model.setDueRule(kind, value)
    }

    private func daysText(_ days: Int) -> String {
        if days == 0 { return String(localized: "notif.sameDay") }
        return String(format: String(localized: "notif.daysBefore"), days)
    }

    @ViewBuilder
    private func ruleBlock(_ kind: String) -> some View {
        let value: DueRuleValue = current(kind)
        let maxDays: Int = Int(DueBridge.shared.maxDays())
        VStack(alignment: .leading, spacing: 4) {
            Toggle(isOn: Binding<Bool>(
                get: { current(kind).enabled },
                set: { on in change(kind, { $0.enabled = on }) }
            )) {
                Text(kindTitle(kind)).font(.body)
            }
            .frame(minHeight: 48)
            if value.enabled {
                Stepper(
                    daysText(value.daysBefore),
                    value: Binding<Int>(
                        get: { current(kind).daysBefore },
                        set: { days in change(kind, { $0.daysBefore = days }) }
                    ),
                    in: 0...maxDays
                )
                .frame(minHeight: 48)
                DatePicker(
                    String(localized: "notif.time"),
                    selection: Binding<Date>(
                        get: { DueSettings.date(minute: current(kind).minute) },
                        set: { date in change(kind, { $0.minute = DueSettings.minute(of: date) }) }
                    ),
                    displayedComponents: .hourAndMinute
                )
                .frame(minHeight: 48)
            }
        }
        Hairline()
    }

    // MARK: Daily reminder

    private var reminderBlock: some View {
        VStack(alignment: .leading, spacing: 4) {
            Toggle(isOn: Binding<Bool>(
                get: { reminderOn },
                set: { on in
                    reminderOn = on
                    model.setReminder(on: on, minute: reminderMinute)
                }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("notif.reminder").font(.body)
                    Text("notif.reminderDetail").font(.footnote).foregroundColor(Palette.ink2)
                }
            }
            .frame(minHeight: 48)
            if reminderOn {
                DatePicker(
                    String(localized: "notif.time"),
                    selection: Binding<Date>(
                        get: { DueSettings.date(minute: reminderMinute) },
                        set: { date in
                            reminderMinute = DueSettings.minute(of: date)
                            model.setReminder(on: reminderOn, minute: reminderMinute)
                        }
                    ),
                    displayedComponents: .hourAndMinute
                )
                .frame(minHeight: 48)
            }
        }
    }
}
