package app.kassa.money.ui.coach

import app.kassa.money.ui.Page

/**
 * The one switch for Coach on Android. While [VISIBLE] is false nothing of Coach can be reached: no Overview card, no Settings > Coach, no
 * Coach, check-in or simulator pages, and nothing is computed for them. The rules, the screens, the strings, the settings keys and the tests
 * all stay; flip this to true (and `CoachFeature.visible` in ios/Kassa/CoachRules.swift) to bring Coach back.
 */
object CoachFeature {
    const val VISIBLE = false

    /** True for every page that belongs to Coach. */
    fun isCoachPage(page: Page): Boolean = page is Page.Coach || page is Page.CheckIn || page is Page.CanIAfford || page is Page.SimulateMonth

    /** False for a Coach page while Coach is hidden, so a route to one (even a stale one) is ignored. [visible] is injectable for tests. */
    fun allows(page: Page, visible: Boolean = VISIBLE): Boolean = visible || !isCoachPage(page)
}
