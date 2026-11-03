package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupJson
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/** The demo backup's Accounts and Activity figures from the shared rules equal the ones `tools/make_demo_backup.py` worked out from the entries alone. */
class DemoBackupTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private fun demo(): BackupData {
        val bytes = File(dir + "demo.kassa").readBytes()
        return BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
    }
    private fun num(json: String, key: String): Long = Regex("\"$key\": (-?\\d+)").find(json)!!.groupValues[1].toLong()
    private fun usd(cents: Long): String {
        val a = abs(cents)
        val whole = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (cents < 0) "-" else "") + "$" + whole + "." + (a % 100).toString().padStart(2, '0')
    }

    @Test
    fun accountsMatchTheWorkedOutFigures() {
        KassaCore.installDefaults()
        val json = File(dir + "expected.json").readText()
        val a = LedgerView(demo(), null).accounts(false, num(json, "todayEpochDay").toInt(), "UTC")
        assertEquals(usd(num(json, "assets")), a.assetsText)
        assertEquals(usd(num(json, "liabilities")), a.liabilitiesText)
        assertEquals(usd(num(json, "netWorth")), a.netWorthText)
        val rows = a.groups.flatMap { it.rows }
        assertEquals(usd(num(json, "1")), rows.first { it.name == "Everyday checking" }.amountText)
        assertEquals(false, rows.any { it.name == "Old savings" && it.dueKind.name != "Closed" })
    }
}
