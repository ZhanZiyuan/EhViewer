@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ehviewer.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** Room owns admitted connections; this owner also releases failed initialization connections. */
class OwnedRoomDatabase<T : RoomDatabase> internal constructor(val database: T, private val driver: OwnedSQLiteDriver) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    internal val activeConnectionCount: Int get() = driver.activeConnectionCount

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                database.close()
            } finally {
                driver.close()
            }
        }
    }
}

fun <T : RoomDatabase> ownedRoomDatabase(driver: SQLiteDriver, build: (SQLiteDriver) -> T): OwnedRoomDatabase<T> {
    val owned = OwnedSQLiteDriver(driver)
    try {
        return OwnedRoomDatabase(build(owned), owned)
    } catch (failure: Throwable) {
        try {
            owned.close()
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

/** Keep real engine connections until either Room or its enclosing application owner closes them. */
internal class OwnedSQLiteDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate, AutoCloseable {
    private data class State(val closed: Boolean = false, val connections: List<Connection> = emptyList())
    private val state = AtomicReference(State())
    val activeConnectionCount: Int get() = state.load().connections.size

    override fun open(fileName: String): SQLiteConnection {
        check(!state.load().closed) { "Database owner is closed" }
        val connection = Connection(delegate.open(fileName))
        while (true) {
            val before = state.load()
            if (before.closed) {
                connection.close()
                error("Database owner is closed")
            }
            if (state.compareAndSet(before, before.copy(connections = before.connections + connection))) return connection
        }
    }

    override fun close() {
        val connections: List<Connection>
        while (true) {
            val before = state.load()
            if (before.closed) return
            if (state.compareAndSet(before, State(closed = true))) {
                connections = before.connections
                break
            }
        }
        var failure: Throwable? = null
        for (connection in connections) {
            try {
                connection.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private inner class Connection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    delegate.close()
                } finally {
                    while (true) {
                        val before = state.load()
                        if (this !in before.connections || state.compareAndSet(before, before.copy(connections = before.connections - this))) break
                    }
                }
            }
        }
    }
}
