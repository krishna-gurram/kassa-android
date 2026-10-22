package dev.krishnagurram.kassa

import android.app.Application
import dev.krishnagurram.kassa.data.AssistRepository
import dev.krishnagurram.kassa.data.Backup
import dev.krishnagurram.kassa.data.KassaRepository
import dev.krishnagurram.kassa.data.Settings
import dev.krishnagurram.kassa.data.db.KassaDatabase

/** Hand-wired dependencies. Small enough that a DI framework would cost more than it saves. */
class KassaApplication : Application() {
    private val database: KassaDatabase by lazy { KassaDatabase.open(this) }
    val repository: KassaRepository by lazy { KassaRepository(database) }
    val assist: AssistRepository by lazy { AssistRepository(database, repository) }
    val settings: Settings by lazy { Settings(this) }
    val backup: Backup by lazy { Backup(this, database, settings) }
}
