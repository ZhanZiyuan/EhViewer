/*
 * Copyright 2023-2024 Tarsin Norbin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.openInputStream
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings.archivePasswds
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.reader.ArchiveReader
import com.hippo.ehviewer.reader.data.NativeArchiveReader
import com.hippo.ehviewer.spider.copyCbz
import com.hippo.ehviewer.spider.withCbzTemp
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.displayName
import kotlinx.coroutines.coroutineScope
import moe.tarsin.kt.install
import okio.Path
import okio.Path.Companion.toOkioPath

typealias PasswdInvalidator = (String) -> Boolean
typealias PasswdProvider = suspend (PasswdInvalidator) -> String

suspend inline fun <T> useArchivePageLoader(
    file: Path,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    crossinline passwdProvider: PasswdProvider,
    crossinline block: suspend (PageLoader) -> T,
) = autoCloseScope {
    coroutineScope {
        val sort = info == null || file.name.endsWith(".zip")
        val reader = install(openArchiveForReading(file, sort))
        if (reader.needsPassword && archivePasswds.none(reader::providePassword)) {
            archivePasswds += passwdProvider(reader::providePassword)
        }
        val loader = install(
            object : PageLoader(this, info, startPage, reader.pageCount, hasAds) {
                override val title by lazy {
                    if (info != null) {
                        EhUtils.getSuitableTitle(info)
                    } else {
                        FileUtils.getNameFromFilename(file.displayName)!!
                    }
                }

                override fun getImageExtension(index: Int) = reader.extension(index)

                override fun save(index: Int, file: Path) = runCatching {
                    file.openFileDescriptor("w").use {
                        reader.copyTo(index, it.fd)
                    }
                }.getOrElse {
                    logcat(it)
                    false
                }

                override fun openSource(index: Int): ImageSource {
                    val page = reader.read(index)
                    return byteBufferSource(page.buffer) { page.close() }
                }

                override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

                override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) = notifySourceReady(index)
            },
        )
        block(loader)
    }
}

@PublishedApi
internal suspend fun openArchiveForReading(file: Path, sort: Boolean): ArchiveReader {
    file.openFileDescriptor("r").use { descriptor ->
        val length = descriptor.statSize
        if (length > 0) return NativeArchiveReader.open(descriptor.fd, length, sort)
    }
    // A document provider can expose a pipe instead of a seekable file.
    return withCbzTemp { local ->
        file.openInputStream().use { input -> local.outputStream().use { copyCbz(input, it) } }
        local.toOkioPath().openFileDescriptor("r").use { descriptor ->
            NativeArchiveReader.open(descriptor.fd, descriptor.statSize, sort)
        }
    }
}
