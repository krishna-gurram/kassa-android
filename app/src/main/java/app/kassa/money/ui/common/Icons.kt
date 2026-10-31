package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.AccountType
import app.kassa.money.ui.theme.Kassa

/** One icon per kind of account, so a list of accounts reads at a glance. */
fun accountIcon(type: AccountType): ImageVector = when (type) {
    AccountType.CHECKING -> Icons.Outlined.AccountBalance
    AccountType.SAVINGS -> Icons.Outlined.Savings
    AccountType.FIXED_DEPOSIT -> Icons.Outlined.Lock
    AccountType.CASH -> Icons.Outlined.Payments
    AccountType.CREDIT_CARD -> Icons.Outlined.CreditCard
    AccountType.INVESTMENT -> Icons.Outlined.ShowChart
    AccountType.LOAN -> Icons.Outlined.RequestQuote
    AccountType.PREPAID -> Icons.Outlined.AccountBalanceWallet
    AccountType.OTHER_ASSET -> Icons.Outlined.Inventory2
    AccountType.OTHER_DEBT -> Icons.Outlined.Receipt
}

/** A neutral circle holding an icon: accounts, transfers and other things that aren't categories. */
@Composable
fun IconBadge(icon: ImageVector, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Kassa.colors.chip), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Kassa.colors.ink2, modifier = Modifier.size((size * 0.5).dp))
    }
}
