package dev.krishnagurram.kassa.data

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import dev.krishnagurram.kassa.data.db.AccountEntity
import dev.krishnagurram.kassa.data.db.AlertRuleEntity
import dev.krishnagurram.kassa.data.db.BudgetEntity
import dev.krishnagurram.kassa.data.db.CategoryEntity
import dev.krishnagurram.kassa.data.db.GroupEntity
import dev.krishnagurram.kassa.data.db.HoldingEntity
import dev.krishnagurram.kassa.data.db.KassaDatabase
import dev.krishnagurram.kassa.data.db.LoanEntryEntity
import dev.krishnagurram.kassa.data.db.PersonEntity
import dev.krishnagurram.kassa.data.db.RecurringEntity
import dev.krishnagurram.kassa.data.db.TransactionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Kassa's own backup: everything in one file, encrypted with a passphrase you choose. The database file itself
 * can't be copied to another phone (its key lives in this phone's Keystore), so this is how data moves or survives
 * a reset. Format: "KASSA1", 16-byte salt, 12-byte IV, then AES-256-GCM over gzipped JSON. The key comes from
 * PBKDF2-HMAC-SHA256 with 210,000 iterations.
 */
class Backup(private val context: Context, private val db: KassaDatabase, private val settings: Settings) {

    class WrongPassphrase : Exception("That passphrase doesn't open this backup.")
    class NotABackup : Exception("This file isn't a Kassa backup.")

    suspend fun write(out: OutputStream, passphrase: CharArray) {
        val json = JSONObject()
            .put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("monthStartDay", settings.monthStartDay.value)
            .put("groups", arr(db.groups().all()) { o, e -> o.put("id", e.id).put("name", e.name).put("sortOrder", e.sortOrder) })
            .put("accounts", arr(db.accounts().all()) { o, e ->
                o.put("id", e.id).put("name", e.name).put("type", e.type).putOpt("groupId", e.groupId).put("openingBalance", e.openingBalance)
                    .putOpt("statementDay", e.statementDay).putOpt("dueDay", e.dueDay).putOpt("payFromId", e.payFromId).putOpt("creditLimit", e.creditLimit)
                    .put("archived", e.archived).put("sortOrder", e.sortOrder).putOpt("externalId", e.externalId).putOpt("last4", e.last4)
            })
            .put("categories", arr(db.categories().all()) { o, e ->
                o.put("id", e.id).put("name", e.name).put("kind", e.kind).putOpt("parentId", e.parentId).put("icon", e.icon).put("color", e.color)
                    .put("countsTowardDaily", e.countsTowardDaily).put("hidden", e.hidden).put("sortOrder", e.sortOrder).putOpt("externalId", e.externalId)
            })
            .put("transactions", arr(db.transactions().all()) { o, e ->
                o.put("id", e.id).put("kind", e.kind).put("amount", e.amount).putOpt("accountId", e.accountId).putOpt("toAccountId", e.toAccountId)
                    .putOpt("categoryId", e.categoryId).putOpt("personId", e.personId).put("epochMillis", e.epochMillis).put("note", e.note)
                    .putOpt("myShare", e.myShare).putOpt("externalId", e.externalId)
            })
            .put("budgets", arr(db.budgets().all()) { o, e -> o.put("categoryId", e.categoryId).put("month", e.month).put("amount", e.amount) })
            .put("people", arr(db.people().all()) { o, e -> o.put("id", e.id).put("name", e.name).put("archived", e.archived) })
            .put("loans", arr(db.loans().all()) { o, e ->
                o.put("id", e.id).put("personId", e.personId).put("amount", e.amount).put("epochMillis", e.epochMillis).put("note", e.note).putOpt("txId", e.txId)
            })
            .put("recurring", arr(db.recurring().all()) { o, e ->
                o.put("id", e.id).put("name", e.name).put("categoryId", e.categoryId).put("accountId", e.accountId).put("dayOfMonth", e.dayOfMonth)
                    .putOpt("fixedAmount", e.fixedAmount).put("active", e.active).put("handledMonth", e.handledMonth)
            })
            .put("holdings", arr(db.holdings().all()) { o, e ->
                o.put("id", e.id).put("accountId", e.accountId).put("ticker", e.ticker).put("microShares", e.microShares).put("price", e.price).put("priceMillis", e.priceMillis)
            })
            .put("alertRules", arr(db.alertRules().all()) { o, e -> o.put("id", e.id).put("packageName", e.packageName).putOpt("last4", e.last4).put("accountId", e.accountId) })
            .put("icons", JSONObject().also { icons ->
                java.io.File(context.filesDir, "icons").listFiles()?.forEach { f -> icons.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) }
            })

        val plain = ByteArrayOutputStream().also { bos -> GZIPOutputStream(bos).use { it.write(json.toString().toByteArray()) } }.toByteArray()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)) }
        out.write(MAGIC); out.write(salt); out.write(iv); out.write(cipher.doFinal(plain))
    }

    /** Reads and decrypts a backup without changing anything. Returns a short summary for the confirm step. */
    fun read(input: InputStream, passphrase: CharArray): Pair<JSONObject, String> {
        val bytes = input.readBytes()
        if (bytes.size < MAGIC.size + 28 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackup()
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = bytes.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)) }
        val plain = try { cipher.doFinal(bytes, MAGIC.size + 28, bytes.size - MAGIC.size - 28) } catch (e: AEADBadTagException) { throw WrongPassphrase() }
        val json = JSONObject(GZIPInputStream(ByteArrayInputStream(plain)).readBytes().decodeToString())
        val created = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(json.optLong("createdAt")))
        val summary = "From $created: ${json.getJSONArray("transactions").length()} entries, ${json.getJSONArray("accounts").length()} accounts, ${json.getJSONArray("categories").length()} categories."
        return json to summary
    }

    /** Replaces everything with the backup's contents, in one database transaction. */
    suspend fun restore(json: JSONObject) {
        db.withTransaction {
            db.transactions().deleteAll(); db.budgets().deleteAll(); db.categories().deleteAll(); db.accounts().deleteAll(); db.groups().deleteAll()
            db.people().deleteAll(); db.loans().deleteAll(); db.recurring().deleteAll(); db.pending().deleteAll(); db.holdings().deleteAll(); db.alertRules().deleteAll()
            db.groups().insertAll(list(json, "groups") { GroupEntity(it.getLong("id"), it.getString("name"), it.getInt("sortOrder")) })
            db.accounts().insertAll(list(json, "accounts") {
                AccountEntity(it.getLong("id"), it.getString("name"), it.getString("type"), it.optLongOrNull("groupId"), it.getLong("openingBalance"),
                    it.optIntOrNull("statementDay"), it.optIntOrNull("dueDay"), it.optLongOrNull("payFromId"), it.optLongOrNull("creditLimit"),
                    it.getBoolean("archived"), it.getInt("sortOrder"), it.optStringOrNull("externalId"), it.optStringOrNull("last4"))
            })
            db.categories().insertAll(list(json, "categories") {
                CategoryEntity(it.getLong("id"), it.getString("name"), it.getString("kind"), it.optLongOrNull("parentId"), it.getString("icon"), it.getInt("color"),
                    it.getBoolean("countsTowardDaily"), it.getBoolean("hidden"), it.getInt("sortOrder"), it.optStringOrNull("externalId"))
            })
            db.transactions().insertAll(list(json, "transactions") {
                TransactionEntity(it.getLong("id"), it.getString("kind"), it.getLong("amount"), it.optLongOrNull("accountId"), it.optLongOrNull("toAccountId"),
                    it.optLongOrNull("categoryId"), it.optLongOrNull("personId"), it.getLong("epochMillis"), it.getString("note"), it.optLongOrNull("myShare"), it.optStringOrNull("externalId"))
            })
            db.budgets().insertAll(list(json, "budgets") { BudgetEntity(it.getLong("categoryId"), it.getInt("month"), it.getLong("amount")) })
            db.people().insertAll(list(json, "people") { PersonEntity(it.getLong("id"), it.getString("name"), it.getBoolean("archived")) })
            db.loans().insertAll(list(json, "loans") { LoanEntryEntity(it.getLong("id"), it.getLong("personId"), it.getLong("amount"), it.getLong("epochMillis"), it.getString("note"), it.optLongOrNull("txId")) })
            db.recurring().insertAll(list(json, "recurring") {
                RecurringEntity(it.getLong("id"), it.getString("name"), it.getLong("categoryId"), it.getLong("accountId"), it.getInt("dayOfMonth"),
                    it.optLongOrNull("fixedAmount"), it.getBoolean("active"), it.getInt("handledMonth"))
            })
            db.holdings().insertAll(list(json, "holdings") {
                HoldingEntity(it.getLong("id"), it.getLong("accountId"), it.getString("ticker"), it.getLong("microShares"), it.getLong("price"), it.getLong("priceMillis"))
            })
            db.alertRules().insertAll(list(json, "alertRules") { AlertRuleEntity(it.getLong("id"), it.getString("packageName"), it.optStringOrNull("last4"), it.getLong("accountId")) })
        }
        json.optJSONObject("icons")?.let { icons ->
            val dir = java.io.File(context.filesDir, "icons").apply { mkdirs() }
            icons.keys().forEach { name -> java.io.File(dir, name).writeBytes(Base64.decode(icons.getString(name), Base64.NO_WRAP)) }
        }
        settings.setMonthStartDay(json.optInt("monthStartDay", 1))
    }

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, 210_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun <T> arr(items: List<T>, fill: (JSONObject, T) -> JSONObject): JSONArray = JSONArray().also { a -> items.forEach { a.put(fill(JSONObject(), it)) } }

    private fun <T> list(json: JSONObject, name: String, map: (JSONObject) -> T): List<T> {
        val a = json.optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).map { map(a.getJSONObject(it)) }
    }

    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    private companion object {
        val MAGIC = "KASSA1".toByteArray()
    }
}
