package dev.krishnagurram.kassa

import android.app.Application
import dev.krishnagurram.kassa.data.KassaRepository
import dev.krishnagurram.kassa.data.db.KassaDatabase

/** Hand-wired dependencies. Small enough that a DI framework would cost more than it saves. */
class KassaApplication : Application() {
    val repository: KassaRepository by lazy { KassaRepository(KassaDatabase.open(this)) }
}
