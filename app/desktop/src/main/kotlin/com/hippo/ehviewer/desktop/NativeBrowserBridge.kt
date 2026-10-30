package com.hippo.ehviewer.desktop

import com.ehviewer.core.network.session.BrowserSessionCookie
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal interface BrowserCalls {
    fun protocolVersion(): Int
    fun open(binary: String, userAgent: String, regression: Boolean): Long
    fun cookies(handle: Long): ByteArray
    fun close(handle: Long)
    fun running(handle: Long): Boolean
    fun processId(handle: Long): Long
    fun active(): Int
}

/** JNI signatures match the existing Rust JVM boundary; no process or cookie file is owned by Kotlin. */
internal class NativeBrowserBridge : BrowserCalls {
    external override fun protocolVersion(): Int
    external override fun open(binary: String, userAgent: String, regression: Boolean): Long
    external override fun cookies(handle: Long): ByteArray
    external override fun close(handle: Long)
    external override fun running(handle: Long): Boolean
    external override fun processId(handle: Long): Long
    external override fun active(): Int
}

internal fun decodeBrowserCookies(bytes: ByteArray): List<BrowserSessionCookie> {
    require(bytes.size <= 256 * 1024)
    val records = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonArray
    require(records.size <= 128)
    return records.map { value ->
        val record = value.jsonObject
        require(record.keys == setOf("name", "value", "domain", "path", "secure", "httpOnly", "expiresAt"))
        fun text(name: String) = record.getValue(name).jsonPrimitive.content
        BrowserSessionCookie(
            text("name"),
            text("value"),
            text("domain"),
            text("path"),
            record.getValue("secure").jsonPrimitive.boolean,
            record.getValue("httpOnly").jsonPrimitive.boolean,
            record.getValue("expiresAt").let { if (it == JsonNull) null else it.jsonPrimitive.long },
        )
    }
}
