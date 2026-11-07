package com.ehviewer.core.preferences

import com.ehviewer.core.database.portableDataStore

internal actual fun getDateStore(name: String?) = portableDataStore(name)
