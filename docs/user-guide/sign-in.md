# Sign-in and account access

EhViewer uses your forum account to authenticate supported gallery-site features. The in-app WebView and the app share the Android WebView cookie store.

## Sign in

- **Sign in** submits your username and password from the app form. With a saved identity, it first checks the existing forum session.
- **Sign in via WebView** opens the forum sign-in page in the app, or checks the existing session first. Complete the forum form in that window. The app continues when the forum confirms the session and the required cookies are available.
- **Guest mode** opens E-Hentai for features available without an account. Account features such as remote favorites require sign-in.

To switch accounts, open **Settings → EH**, select the account name, and confirm **Sign out**. If the initial sign-in screen shows **Sign out**, there is a saved or partial session that you can explicitly clear from that screen.

## Common problems

| Problem | What to check |
| --- | --- |
| The app form reports **Sign in failed** | Check the username and password, then try **Sign in via WebView**. If the forum itself shows an error, resolve it on the forum page before returning to the app. |
| The WebView shows a browser check or a connection error | Let the forum page finish loading. Check the network and install available updates for your device's WebView provider. Retry in the app so both the page and its session remain in the same WebView. |
| The sign-in screen shows **Sign out** even though you cannot enter | Try **Sign in via WebView** to verify the saved session. For a session that still fails, confirm **Sign out** and start a new sign-in or choose **Guest mode**. |
| E-Hentai works but the other gallery site reports an access error | Gallery-site access is checked separately from forum login. Use **Settings → EH → Gallery site** to choose an accessible site. The website controls account and site eligibility. |
| A connection closes during a request | Check connectivity and retry. If the problem persists with Cronet enabled, try changing **Settings → Advanced → Enable Cronet** and report which setting and Android version you used. |

When reporting a sign-in problem, include the app version, Android version, selected gallery site, the step that failed, and the visible error. Keep passwords and identity cookies private. Remove account data from any screenshots or logs you share.
