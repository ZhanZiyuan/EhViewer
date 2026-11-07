package com.ehviewer.core.database

import androidx.room.RoomDatabaseConstructor

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object EhDatabaseConstructor : RoomDatabaseConstructor<EhDatabase> {
    override fun initialize(): EhDatabase
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object SearchDatabaseConstructor : RoomDatabaseConstructor<SearchDatabase> {
    override fun initialize(): SearchDatabase
}
