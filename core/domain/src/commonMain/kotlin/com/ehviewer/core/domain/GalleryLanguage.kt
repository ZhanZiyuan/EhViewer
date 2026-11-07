package com.ehviewer.core.domain

/** Null follows the system. Preserve valid, unsupported tags rather than replacing a user's choice. */
interface AppLanguageStore {
    suspend fun tag(): String?
    suspend fun setTag(tag: String?)
}

class AppLanguageException : Exception("Language preferences could not be loaded or saved")

fun validateAppLanguageTag(tag: String?) {
    if (tag == null) return
    require(tag.length in 2..64 && Regex("(?:[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*|[iIxX](?:-[A-Za-z0-9]{1,8})+)").matches(tag)) { "Invalid application language tag" }
}
