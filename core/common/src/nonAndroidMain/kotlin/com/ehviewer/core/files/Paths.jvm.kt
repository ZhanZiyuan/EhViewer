package com.ehviewer.core.files

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path as IoPath
import kotlinx.io.files.SystemFileSystem as IoFileSystem
import okio.Path

actual inline fun <T> Path.read(f: Source.() -> T): T = IoFileSystem.source(IoPath(toString())).buffered().use(f)

actual inline fun <T> Path.write(f: Sink.() -> T): T = IoFileSystem.sink(IoPath(toString())).buffered().use(f)
