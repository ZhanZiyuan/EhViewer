package com.hippo.ehviewer.client.parser

import com.hippo.ehviewer.client.exception.ParseException
import java.nio.ByteBuffer

object UserConfigParser {
    private external fun parseFavCat(body: ByteBuffer, size: Int = body.limit()): Int

    fun parse(body: ByteBuffer) = runCatching {
        unmarshalParsingAs<Array<String>>(body, ::parseFavCat)
    }.onFailure {
        throw ParseException("Can't parse user config", it)
    }.getOrThrow()
}
