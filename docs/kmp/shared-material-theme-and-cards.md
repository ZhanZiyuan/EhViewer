# Shared Material 3 theme and list cards

The shared screens no longer use Compose's plain default light/dark colors. On Android, SharedGalleriesScreen passes the app's original EhTheme, so the shared screens get the same Dynamic Color (Android 12+), wallpaper-palette (8.1–11), AMOLED black and expressive motion as the rest of v1.15.10. Where the shared library runs without that theme, GalleryTheme applies Android Dynamic Color on Android 12+. Otherwise it uses v1.15.10's fallback: darkColorScheme, or the expressive light scheme. Compose Multiplatform keeps expressiveLightColorScheme and MotionScheme internal outside the AndroidX artifact. The light scheme is therefore rebuilt from its four tone-30 on-container tokens (#4F378B, #4A4458, #633B48, #8C1D18), read from the AndroidX Material 3 bytecode. Desktop and iOS keep standard motion. Desktop and iOS have no wallpaper-derived Material palette, so they use the fallback.

Search results use v1.15.10's GalleryInfoListItem layout:
- Card: a borderless outlined card ("CrystalCard") with a 0.67 cover card.
- Title: titleSmall, two lines.
- Rows in labelLarge: uploader (50% alpha when disowned) with a favorite heart and name; five 16dp yellow #F9A825 half-step stars with language and `nP`; and an uppercase category chip with the posted date.
- Category colors: the original colors, harmonized with primaryContainer in HCT (hue from the category; chroma and tone from the container). This matches the default harmonize_category_color. Text color follows EhUtils' L* rule.
- Card height: list_tile_size 40 × 9 px with a 120dp floor, which matches Android exactly. A 180dp cap keeps density-1 Desktop windows from 360dp cards.

HCT and L* come from material-kolor, which the Android app already uses (Apache-2.0, Kotlin Multiplatform). Icons are local Apache-2.0 Material path data in GalleryIcons.kt.

Not yet matched: the downloaded icon, read-progress `n/mP`, long-press, the list_tile_size preference, the floating search bar, and v1.15.10's 12/20/76dp list margins. Making core:ui multiplatform would let all screens reuse CrystalCard and the rating widget directly. It would also add the large Material icon artifact to the Desktop and iOS release assets, so it is left for a separate decision.

Local results: Android spotless/unit22/host60/lint/assemble and shared Desktop76 passed, including the new category-name and card-height tests. iOS arm64/simulator compilation and the debug link passed. Serial phone50 and tablet50 runs passed with zero skips. Headless light/dark renders of synthetic cards are kept under ignored build/kmp-work-logs/m3-theme-cards-phone. Real cover/site rendering on Desktop and iPad was not recorded for this change. Animeko was not consulted, and no AGPL material is used.
