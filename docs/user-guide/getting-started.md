# Getting started

## Choose an APK

EhViewer is an Android gallery browser and reader. Install the APK from this project's [GitHub Releases](https://github.com/ZhanZiyuan/EhViewer/releases).

| Device architecture | APK variant |
| --- | --- |
| 64-bit ARM | `arm64-v8a` |
| 32-bit ARM | `armeabi-v7a` |
| 64-bit x86 | `x86_64` |
| Architecture unknown | `universal` |

Android may ask you to allow installation from the browser or file manager that opens the APK. For an in-place upgrade, use a newer build with the same application ID and signing key as your installed copy. Export your data before switching to a different distribution. See [data export and import](using-ehviewer.md#export-and-import-app-data).

## Android versions and screen sizes

Current builds require Android 8.0 Oreo (API 26) or later. The app adapts to compact phone windows and wider tablet windows in portrait and landscape. On a narrow window the sign-in form stacks vertically. Wider windows can place the sign-in text and controls side by side. Gallery display options can be adjusted under **Settings → EH**.

## First launch

1. Open EhViewer and review the terms on the sign-in screen.
2. Choose **Sign in**, **Sign in via WebView**, or **Guest mode**. See [sign-in and account access](sign-in.md) for the differences.
3. Open **Homepage** to browse or search. Use the navigation menu to open **Favourite**, **History**, **Downloads**, or **Settings**.
4. Before saving downloads to a shared folder, choose the folder under **Settings → Download → Download location** and grant access in the Android folder picker.

## App language

Open **Settings → Advanced → App language**. The picker offers **System**, English, Japanese, Simplified Chinese (China), Traditional Chinese (Hong Kong), and Traditional Chinese (Taiwan). The choice controls the app interface. Gallery titles and tag translations have their own options under **Settings → EH**.

## Updates

Open **Settings → About → Check for updates** to check for a build. The stable channel uses GitHub Releases. **Use CI snapshot updates** checks for a newer eligible snapshot from the main branch. Snapshot artifacts open in GitHub Actions in your browser. Sign in to GitHub there if prompted, extract the artifact, and install its APK.

See the [changelog](../CHANGELOG_zh-CN.md) for release history or the [user guide](using-ehviewer.md) for common tasks.
