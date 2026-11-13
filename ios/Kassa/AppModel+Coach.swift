import Foundation
import KassaShared

/// Coach (layer 1) on the model: the figures are `LedgerView.coach`, the choices (the two switches, the week whose decision was set aside)
/// are `CoachPrefs` in UserDefaults. Coach never writes to the store.
extension AppModel {

    /// Works Coach's figures out of [made]. Queue only.
    /// nil while Coach is hidden (`CoachFeature.visible`): nothing is worked out for a feature nobody can see.
    func computeCoach(_ made: LedgerView, _ given: Inputs) -> CoachData? {
        if !CoachFeature.visible { return nil }
        return made.coach(
            hidden: given.hidden, todayEpochDay: Int32(given.today), timeZoneId: given.zone, firstWeekday: Int32(given.firstWeekday)
        )
    }

    func openCoach() {
        openRead(ReadRoute.coach)
    }

    func openCheckIn() {
        openRead(ReadRoute.checkIn)
    }

    /// A tapped Coach card: a pace row opens Insights' category page, a subscription the repeating charges page. The fund card goes nowhere.
    func openCoachTarget(_ target: CoachTarget) {
        switch target {
        case .category(let id):
            openCategory(id, monthsBack: 0)
        case .subscriptions:
            openRead(ReadRoute.detail("REPEATING"))
        }
    }

    func setCoachWeekly(_ on: Bool) {
        coachWeekly = on
        CoachPrefs(defaults: defaults).weeklyCheckIn = on
    }

    func setCoachCards(_ on: Bool) {
        coachCards = on
        CoachPrefs(defaults: defaults).insightCards = on
    }

    /// "Not now": the decision of the week starting on [weekStart] stays out for that week.
    func dismissCheckIn(weekStart: Int) {
        coachDismissed = weekStart
        CoachPrefs(defaults: defaults).dismissedWeek = weekStart
    }
}
