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
            TextButton(onClick = ::back) { Text("Back") }
            Text("Account", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (checking || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            failure?.let { error ->
                Text(if (error == GalleryFailure.AUTHENTICATION) "Sign-in failed or the session has expired. Check your details and try again." else error.userMessage(), color = MaterialTheme.colorScheme.error)
            }
            when (signedIn) {
                true -> {
                    Text(overview?.profile?.displayName ?: "Signed in", style = MaterialTheme.typography.titleLarge)
                    overview?.let { account ->
                        Text("Favorite categories", style = MaterialTheme.typography.titleMedium)
                        account.favoriteCategories.forEachIndexed { index, name -> Text("${index + 1}. $name") }
                        Text(
                            when (account.restrictedAccess) {
                                RestrictedSiteAccess.AVAILABLE -> "Restricted site available"
                                RestrictedSiteAccess.UNAVAILABLE -> "Restricted site is unavailable for this session"
                                RestrictedSiteAccess.UNKNOWN -> "Restricted site could not be checked. Refresh to try again."
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy && !checking && restricted, onClick = {
                                onIdentityAction()
                                session.selectSite(false)
                                restricted = false
                            }) { Text(if (restricted) "Use E-Hentai" else "E-Hentai selected") }
                            TextButton(enabled = !busy && !checking && !restricted && account.restrictedAccess == RestrictedSiteAccess.AVAILABLE, onClick = {
                                onIdentityAction()
                                session.selectSite(true)
                                restricted = true
                            }) { Text(if (restricted) "ExHentai selected" else "Use ExHentai") }
                        }
                    }
                    TextButton(onClick = {
                        overview = null
                        checkAttempt++
                    }, enabled = !busy && !checking) { Text("Refresh account") }
                    Button(onClick = { perform(signOut = true) }, enabled = !busy && !checking) { Text("Sign out") }
                }
                false -> {
                    Text("Sign in to your account")
                    OutlinedTextField(username, { username = it.take(256) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Username") }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next))
                    OutlinedTextField(
                        password, { password = it.take(4096) }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!busy && username.isNotBlank() && password.isNotEmpty()) perform(signOut = false) }),
                    )
                    Button(onClick = { perform(signOut = false) }, enabled = !busy && !checking && username.isNotBlank() && password.isNotEmpty()) { Text("Sign in") }
                    if (browserFactory != null) {
                        TextButton(enabled = !busy && !checking, onClick = {
                            password = ""
                            onIdentityAction()
                            browser = true
                        }) { Text("Sign in with browser") }
                    }
                }
                null -> if (!checking) TextButton(onClick = { checkAttempt++ }) { Text("Retry saved session") }
            }
        }
    }
}
