package app.kassa.money.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.R
import app.kassa.money.domain.Money
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.coach.CoachModel
import app.kassa.money.ui.coach.SimModel
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** Settings > Coach: the two switches (phone preferences), a way into Coach, and what a question would send. */
@Composable
internal fun CoachSettingsPage(actions: SettingsActions, openSends: () -> Unit) {
    val settings = LocalSettings.current
    val checkIn by settings.coachCheckIn.collectAsState()
    val cards by settings.coachCards.collectAsState()
    SectionHeader(stringResource(R.string.coach_settings_section), top = 0.dp)
    SwitchRow(stringResource(R.string.coach_settings_checkin), stringResource(R.string.coach_settings_checkin_detail), checkIn, settings::setCoachCheckIn)
    SwitchRow(stringResource(R.string.coach_settings_cards), stringResource(R.string.coach_settings_cards_detail), cards, settings::setCoachCards)
    SectionHeader(stringResource(R.string.coach_sim_entry_title))
    val cushion by settings.coachCushion.collectAsState()
    var cushionText by rememberSaveable { mutableStateOf(if (cushion == 0L) "" else Money.toInput(cushion)) }
    val cushionCents = SimModel.cushionFromText(cushionText)
    AmountField(
        value = cushionText,
        onChange = { cushionText = it; SimModel.cushionFromText(it)?.let(settings::setCoachCushion) },
        label = stringResource(R.string.coach_sim_cushion), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        problem = if (cushionCents == null) stringResource(R.string.coach_sim_cushion_bad) else null,
        placeholder = Money.toInput(0L),
    )
    Text(stringResource(R.string.coach_sim_cushion_detail), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    NavRow(stringResource(R.string.coach_open), "") { actions.openCoach() }
    NavRow(stringResource(R.string.coach_sends_title), stringResource(R.string.coach_sends_value), openSends)
    Gap(24)
}

/**
 * What a question sends: the facts Coach would carry (`CoachFacts`, totals only), as `key: value` lines. Nothing is sent today; this is the whole
 * list, shown so it can be read. While Hide balances is on every value but the days is the mask.
 */
@Composable
internal fun CoachSendsPage(actions: SettingsActions) {
    val report by actions.coach.collectAsState()
    val hidden = Money.isHidden
    val rows = remember(report, hidden) { report?.let { CoachModel.factRows(it.facts, hidden) } ?: emptyList() }
    Text(stringResource(R.string.coach_sends_intro), style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 12.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Kassa.colors.raised).border(1.dp, Kassa.colors.line, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        Text(stringResource(R.string.coach_sends_title).uppercase(java.util.Locale.getDefault()), style = KassaType.label, color = Kassa.colors.ink2)
        Gap(8)
        rows.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text("$key: $value", style = KassaType.small.copy(fontFamily = FontFamily.Monospace), color = Kassa.colors.ink, softWrap = true, overflow = TextOverflow.Clip)
            }
        }
        Text(stringResource(R.string.coach_sends_none), style = KassaType.small.copy(fontFamily = FontFamily.Monospace), color = Kassa.colors.ink3, modifier = Modifier.padding(top = 8.dp))
    }
    Text(stringResource(R.string.coach_sends_footer), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 12.dp))
    Gap(24)
}
