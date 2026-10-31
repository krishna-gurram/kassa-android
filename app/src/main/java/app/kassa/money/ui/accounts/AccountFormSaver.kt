package app.kassa.money.ui.accounts

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import app.kassa.money.domain.AccountForm
import app.kassa.money.domain.AccountType

/** Keeps an account form's typed fields across the activity being recreated (folding, rotating), as plain strings. */
val AccountFormSaver: Saver<AccountForm, Any> = listSaver(
    save = { f ->
        listOf(
            f.type.name, f.name, f.last4, f.balance, f.statementDay, f.dueDay, f.limit,
            f.payFromId?.toString().orEmpty(), f.groupId?.toString().orEmpty(), f.newGroup, f.maturityDay?.toString().orEmpty(),
        )
    },
    restore = { v ->
        if (v.size < 11) null
        else AccountForm(
            type = AccountType.entries.firstOrNull { it.name == v[0] } ?: AccountType.CHECKING,
            name = v[1], last4 = v[2], balance = v[3], statementDay = v[4], dueDay = v[5], limit = v[6],
            payFromId = v[7].toLongOrNull(), groupId = v[8].toLongOrNull(), newGroup = v[9], maturityDay = v[10].toIntOrNull(),
        )
    },
)
