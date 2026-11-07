package com.ehviewer.core.files

import okio.FileSystem

actual val SystemFileSystem: FileSystem
    get() = FileSystem.SYSTEM
