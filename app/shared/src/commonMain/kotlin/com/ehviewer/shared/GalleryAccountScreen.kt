package com.ehviewer.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ehviewer.core.network.GallerySession
import com.ehviewer.core.network.gallery.GalleryFailure
import com.ehviewer.core.network.gallery.GalleryRequestException
import com.ehviewer.core.network.session.GalleryAccountOverview
import com.ehviewer.core.network.session.RestrictedSiteAccess
import com.ehviewer.core.network.session.SessionStorageException
import ehviewer.app.shared.generated.resources.Res
import ehviewer.app.shared.generated.resources.gallery_account
import ehviewer.app.shared.generated.resources.gallery_account_refresh
import ehviewer.app.shared.generated.resources.gallery_back
import ehviewer.app.shared.generated.resources.gallery_browser_sign_in
import ehviewer.app.shared.generated.resources.gallery_favorite_categories
import ehviewer.app.shared.generated.resources.gallery_password
import ehviewer.app.shared.generated.resources.gallery_restricted_available
import ehviewer.app.shared.generated.resources.gallery_restricted_check_failed
import ehviewer.app.shared.generated.resources.gallery_restricted_unavailable
import ehviewer.app.shared.generated.resources.gallery_session_retry
import ehviewer.app.shared.generated.resources.gallery_sign_in
import ehviewer.app.shared.generated.resources.gallery_sign_in_failed
import ehviewer.app.shared.generated.resources.gallery_sign_in_title
import ehviewer.app.shared.generated.resources.gallery_sign_out
import ehviewer.app.shared.generated.resources.gallery_signed_in
import ehviewer.app.shared.generated.resources.gallery_site_eh_selected
import ehviewer.app.shared.generated.resources.gallery_site_eh_use
import ehviewer.app.shared.generated.resources.gallery_site_ex_selected
import ehviewer.app.shared.generated.resources.gallery_site_ex_use
import ehviewer.app.shared.generated.resources.gallery_username
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Common account UI. Credentials stay in composition memory and never enter saved instance state. */
@Composable
internal fun GalleryAccountScreen(session: GallerySession, browserFactory: ((String) -> GalleryLoginBrowser)?, onIdentityAction: () -> Unit, onBack: () -> Unit) {
    var signedIn by remember { mutableStateOf<Boolean?>(null) }
    var overview by remember { mutableStateOf<GalleryAccountOverview?>(null) }
    var restricted by remember { mutableStateOf(session.restricted) }
    var checking by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<GalleryFailure?>(null) }
    var checkAttempt by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf(false) }
    if (browser && browserFactory != null) {
        GalleryBrowserSignInScreen(session, browserFactory, onBack = { browser = false }, onSignedIn = {
            browser = false
            checkAttempt++
        })
        return
    }
    LaunchedEffect(session, checkAttempt) {
        checking = true
        try {
            signedIn = session.cookies.isSignedIn()
            failure = null
            overview = if (signedIn == true) session.account.refresh() else null
            if (restricted && overview?.restrictedAccess != RestrictedSiteAccess.AVAILABLE) {
                session.selectSite(false)
                restricted = false
                onIdentityAction()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: GalleryRequestException) {
            failure = error.failure
        } catch (_: SessionStorageException) {
            failure = GalleryFailure.SESSION_STORAGE
        } finally {
            checking = false
        }
    }
    fun back() {
        operation?.cancel()
        password = ""
        onBack()
    }
    fun perform(signOut: Boolean) {
        val current = ++generation
        val submittedUsername = username
        val submittedPassword = password
        operation?.cancel()
        onIdentityAction()
        operation = scope.launch {
            busy = true
            failure = null
            try {
                overview = null
                if (signOut) {
                    session.cookies.logout()
                    session.selectSite(false)
                    restricted = false
                } else {
                    session.authentication.signIn(submittedUsername, submittedPassword)
                }
                signedIn = session.cookies.isSignedIn()
                if (!signOut) overview = session.account.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: GalleryRequestException) {
                failure = error.failure
            } catch (_: SessionStorageException) {
                failure = GalleryFailure.SESSION_STORAGE
            } finally {
                if (generation == current) {
                    password = ""
                    busy = false
                }
            }
        }
    }
    BackHandler(onBack = ::back)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::back) { Text(galleryString(Res.string.gallery_back)) }
            Text(galleryString(Res.string.gallery_account), style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (checking || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { error ->
                Text(if (error == GalleryFailure.AUTHENTICATION) galleryString(Res.string.gallery_sign_in_failed) else error.userMessage(), color = MaterialTheme.colorScheme.error)
            }
            when (signedIn) {
                true -> {
                    Text(overview?.profile?.displayName ?: galleryString(Res.string.gallery_signed_in), style = MaterialTheme.typography.titleLarge)
                    overview?.let { account ->
                        Text(galleryString(Res.string.gallery_favorite_categories), style = MaterialTheme.typography.titleMedium)
                        account.favoriteCategories.forEachIndexed { index, name -> Text("${index + 1}. $name") }
                        Text(
                            when (account.restrictedAccess) {
                                RestrictedSiteAccess.AVAILABLE -> galleryString(Res.string.gallery_restricted_available)
                                RestrictedSiteAccess.UNAVAILABLE -> galleryString(Res.string.gallery_restricted_unavailable)
                                RestrictedSiteAccess.UNKNOWN -> galleryString(Res.string.gallery_restricted_check_failed)
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy && !checking && restricted, onClick = {
                                onIdentityAction()
                                session.selectSite(false)
                                restricted = false
                            }) { Text(if (restricted) galleryString(Res.string.gallery_site_eh_use) else galleryString(Res.string.gallery_site_eh_selected)) }
                            TextButton(enabled = !busy && !checking && !restricted && account.restrictedAccess == RestrictedSiteAccess.AVAILABLE, onClick = {
                                onIdentityAction()
                                session.selectSite(true)
                                restricted = true
                            }) { Text(if (restricted) galleryString(Res.string.gallery_site_ex_selected) else galleryString(Res.string.gallery_site_ex_use)) }
                        }
                    }
                    TextButton(onClick = {
                        overview = null
                        checkAttempt++
                    }, enabled = !busy && !checking) { Text(galleryString(Res.string.gallery_account_refresh)) }
                    Button(onClick = { perform(signOut = true) }, enabled = !busy && !checking) { Text(galleryString(Res.string.gallery_sign_out)) }
                }
                false -> {
                    Text(galleryString(Res.string.gallery_sign_in_title))
                    OutlinedTextField(username, { username = it.take(256) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(galleryString(Res.string.gallery_username)) }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next))
                    OutlinedTextField(
                        password, { password = it.take(4096) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text(galleryString(Res.string.gallery_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!busy && username.isNotBlank() && password.isNotEmpty()) perform(signOut = false) }),
                    )
                    Button(onClick = { perform(signOut = false) }, enabled = !busy && !checking && username.isNotBlank() && password.isNotEmpty()) { Text(galleryString(Res.string.gallery_sign_in)) }
                    if (browserFactory != null) {
                        TextButton(enabled = !busy && !checking, onClick = {
                            password = ""
                            onIdentityAction()
                            browser = true
                        }) { Text(galleryString(Res.string.gallery_browser_sign_in)) }
                    }
                }
                null -> if (!checking) TextButton(onClick = { checkAttempt++ }) { Text(galleryString(Res.string.gallery_session_retry)) }
            }
        }
    }
}
