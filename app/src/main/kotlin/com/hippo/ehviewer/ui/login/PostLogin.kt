package com.hippo.ehviewer.ui.login

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.network.IdentityCookies
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.SiteAccess
import com.hippo.ehviewer.client.exception.EhException
import com.hippo.ehviewer.client.exception.NotLoggedInException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tarsin.coroutines.runSuspendCatching

internal class SessionOperation {
    private val active = MutableStateFlow(false)

    suspend fun <T> run(onBusy: () -> Nothing, action: suspend () -> T): T {
        if (!active.compareAndSet(expect = false, update = true)) onBusy()
        try {
            return action()
        } finally {
            active.value = false
        }
    }
}

private val operation = SessionOperation()
private var refreshJob: Job? = null

internal suspend fun <T> changeSession(action: suspend () -> T): T = operation.run({ throw EhException(R.string.sign_in_failed) }, action)

suspend fun refreshAccountInfo() {
    runSuspendCatching { EhEngine.getProfile() }.onSuccess { profile ->
        Settings.withMutableSnapshot {
            it[Settings.displayName] = profile.displayName
            it[Settings.avatar] = profile.avatar
        }
    }
}

internal suspend fun postLogin(displayName: String? = null, cloudflareBypassed: Boolean = false) {
    if (EhCookieStore.identityCookies() != IdentityCookies.Present) throw NotLoggedInException()
    withContext(Dispatchers.IO + NonCancellable) {
        EhCookieStore.flush()
        Settings.withMutableSnapshot {
            if (displayName != null) it[Settings.displayName] = displayName
            if (cloudflareBypassed) it[Settings.desktopSite] = false
            it[Settings.hasSignedIn] = true
            it[Settings.needSignIn] = false
        }

        refreshJob?.cancelAndJoin()
        refreshJob = ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            launch { refreshAccountInfo() }
            launch { runSuspendCatching { EhEngine.getNews(false) } }
            launch {
                val preferences = Settings.snapshot()
                EhEngine.getUConfig(preferences[Settings.gallerySite])
                if (Settings.gallerySite !in preferences && EhEngine.getUConfig(EhUrl.SITE_EX) == SiteAccess.Accessible) {
                    Settings.withMutableSnapshot {
                        if (Settings.gallerySite !in it) it[Settings.gallerySite] = EhUrl.SITE_EX
                    }
                }
                EhCookieStore.flush()
            }
        }
    }
}

internal suspend fun signOutSession() = changeSession {
    withContext(Dispatchers.IO + NonCancellable) {
        refreshJob?.cancelAndJoin()
        EhCookieStore.removeAllCookies()
        Settings.withMutableSnapshot {
            it[Settings.displayName] = null
            it[Settings.avatar] = null
            it[Settings.hasSignedIn] = false
            it[Settings.needSignIn] = true
        }
    }
}

internal fun reconcileSession(history: Boolean, identity: IdentityCookies): Boolean = history && identity == IdentityCookies.Present

internal suspend fun reconcileStoredSession() = Settings.withMutableSnapshot {
    if (it[Settings.hasSignedIn] && !reconcileSession(true, EhCookieStore.identityCookies())) {
        it[Settings.hasSignedIn] = false
    }
}

internal suspend fun returnToSignIn() {
    Settings.withMutableSnapshot { it[Settings.needSignIn] = true }
}
