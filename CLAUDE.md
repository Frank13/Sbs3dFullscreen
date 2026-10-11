# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`sbs3Dfullscreen` is a Windows desktop JPEG viewer built with Kotlin Multiplatform + Compose Desktop. It's designed to 
open one or several JPEG (typically a side-by-side 3D image) in a borderless, maximized window suitable for a 
stereoscopic 3D monitor, and to be launched directly via Windows file association (double-click a `.jpg`/`.jpeg`).

## Commands

- Run in dev mode: `./gradlew.bat run`
- Run the packaged distributable: `./gradlew.bat runDistributable` (or `runRelease` / `runReleaseDistributable`)
- Run tests: `./gradlew.bat desktopTest` (there is a `commonTest` source set wired up via `kotlin("test")`, but no tests 
exist yet)
- Sync common files coming from my Android app Camera 3D : `./tools/sync-from-android.ps1`
- Build Windows installers (MSI/EXE): `./gradlew.bat packageMsi` / `./gradlew.bat packageExe`
  - Requires a JDK that bundles jpackage at `~/.jdks/temurin-21` (set via `javaHome` in `build.gradle.kts`), independent 
  of whatever JDK runs Gradle itself.
  - Bump `appVersion` in `gradle.properties` before every release build — jpackage/Windows Installer only auto-uninstalls 
  the previous version in-place when this number increases; an unchanged version forces users into Add/Remove Programs instead.
- Regenerate the app icon (`icon.png` + `icons/icon.ico` + `icons/icon-square.png`, the square-cornered variant the Store/Start tiles are built from so they don't show black corners): `python ./tools/generate-app-icon.py` (needs Pillow; `GLYPH_SCALE` sets how much of the tile the monitor fills), then `./tools/generate-msix-assets.ps1`
- Build an MSIX for Microsoft Store submission: `./gradlew.bat packageMsix`
  - Wraps the `createDistributable` app-image (no code changes) with a hand-written `packaging/msix/AppxManifest.xml` 
  template + Store logo PNGs (`packaging/msix/Assets/`, regenerated from `icons/icon.ico` by `./tools/generate-msix-assets.ps1` 
  — placeholder-quality, swap for real Store assets before submitting) and packs it with `makeappx.exe`.
  - One-time setup before this produces a submittable package: install the Windows 10/11 SDK (for `makeappx.exe`/`signtool.exe`, 
  not on PATH by default — point `-PmsixMakeAppxPath=...` at it if needed), and fill in `msixPackageIdentityName`/`msixPublisher`/
  `msixPublisherDisplayName` in `gradle.properties` from Partner Center's app name reservation.
  - Output is unsigned: Partner Center signs Store submissions itself; local sideload testing (`Add-AppxPackage`) needs a matching 
  test-cert signature first — `./tools/sign-msix-for-sideload.ps1 [-Install]` creates a self-signed cert from `msixPublisher`, trusts it 
  (first run needs an elevated PowerShell) and writes a signed copy `build/msix/*-sideload.msix`. Never upload that copy to the Store.

## Architecture

WARNING : some files are sync from the Android App fr.camera3d.camera : these files are stored in subdirectories 
starting by fr.camera3d.camera. Never modify them. If you need to modify them you must alert me and if I agree, you 
should modify the Android App original files in `c/Documents/AndroidStudioProjects/CameraSync3D/`. 

Windows app files (all in the default package under `src/desktopMain/kotlin/`, no `src/`-relative imports needed between them):

- `Main.kt`: entry point and the `Window`/`WindowState` shell only — window chrome, focus, key handling, and locale recomposition boundaries. Delegates all screen/navigation state to `AppViewModel`.
- `AppViewModel.kt`: plain (non-Composable) state holder — owns `screen` (`Screen.Welcome`/`Screen.ImageView`), `imageFiles`, `currentImageIndex`, `language`, and the logic to mutate them (`onFilesChosen`, `showNextImage`/`showPreviousImage`, `closeImageView`, `onLanguageChosen`). Backed by `mutableStateOf`, so it's read directly from Composables, but it isn't itself `@Composable` and holds no `Window`/AWT references — window-placement side effects (`WindowState.placement`) stay in `Main.kt`, triggered from the same callbacks that call into the view model.
- `WelcomeScreen.kt`: language picker (EN/FR) + a native `java.awt.FileDialog` (multi-select, JPEG-filtered) to choose images.
- `ImageScreen.kt`: renders the current image full-bleed on black.
- `WhatsNew.kt`: release notes (`allWhatsNewReleases`, newest first, `versionName` must equal `gradle.properties`' `appVersion`) + `WhatsNewTracker` (shown once after an update on the Welcome screen, never on a fresh install) + the dialogs; history reachable from `AboutScreen.kt`. Prepare a release with the `/whats-new-release` skill (`.claude/skills/`), which also reports which Store screenshots are outdated.
- `Cursor3D.kt`: the stereo-duplicated mouse cursor and its click-redirection registry (see below). Used by both `ImageScreen.kt` and `VideoScreen.kt`.
- `LocalAppLocale.kt`: the locale-override composition local (see below).

## Stereo (SBS) display and depth-shift overlays

- The images opened directly by this app (single/multi-file JPEG viewing, launched via file association or the folder gallery) are **full-width side-by-side 3D**: a single JPEG whose left half is the left-eye photo and whose right half is the right-eye photo (see the "SBS image width types" note in memory). Because both eye-halves already live in the one bitmap, `ImageScreen.kt` just draws it once, full-bleed (`Modifier.fillMaxSize()` + `ContentScale.Fit`) — the left/right stereo split falls out of the source image for free, with no per-half rendering needed for the photo itself, and there's no stretch/split logic in that viewer.
- Playlist items (photos, and videos since the Camera3D sync added video-in-playlist support) separately carry a user-editable `isHalfWidth` flag (`PlaylistItem.isHalfWidth`, toggled via `PlaylistItemScreen.kt`'s half-width switch) for a squeezed/half-width source that would need a 2x horizontal stretch to display correctly. That flag is currently only stored/edited — no renderer (`ImageScreen.kt`, `VideoScreen.kt`, or the playlist video-slide path) reads it to actually stretch anything, so half-width playback support remains unimplemented future work, not a bug.
- Anything drawn **on top** of the photo (titles, comments, the EXIF info HUD, …) is different: a single overlay positioned once would sit at the same pixel position in both eye-halves, which reads as pinned flat to the screen glass in 3D rather than floating at a chosen depth. The fix used everywhere in this codebase is to duplicate the overlay once per half (inside a `Row` of two equal-`weight(1f)` boxes spanning `fillMaxSize()`) and shift each copy horizontally in *opposite* directions by a `shiftPercent` of half the screen width:
  ```
  val shift = halfWidth * shiftPercent
  // left half copy:  offset by -shift / 2
  // right half copy: offset by +shift / 2
  ```
  Sign convention: **positive `shiftPercent` pushes the overlay farther behind the screen; negative brings it out toward the viewer.** Typical values are small, -3%..3% (see the `playlist_*_z_documentation` strings). This is the same disparity trick the eyes use to perceive depth — shifting the two copies apart (or together) changes where the brain reconstructs the overlay in Z.
- Reference implementations of this pattern: `PortableSlideshowSlides.kt`'s `ComposablePortableTitleSlide` (title/subtitle, driven by `Playlist.titleZPercent`/`subtitleZPercent`) and `ComposablePortableEndSlide` (animated `zShiftPercent`); `PlaylistItem.commentZPercent` for a photo's comment overlay; and `InfoPanel.kt`'s `InfoPanelShiftPercent` (a fixed -1%, so the Shift/Ctrl-toggled EXIF HUD reads as floating just in front of the screen).

### The 3D mouse cursor (`Cursor3D.kt`)

The mouse cursor gets the same "single overlay reads as pinned to the glass" problem as any other overlay, so `ImageScreen.kt`/`VideoScreen.kt` wrap their content in `Stereo3DCursorHost { ... }`, which:

- Hides the real OS cursor for good (a blank custom `PointerIcon`, same trick the old `CursorAutoHide.kt` used, but permanent instead of idle-timed) and instead draws two small round dots, one per half, offset by a fixed **-1%** `shiftPercent` (see the shift convention above) so it reads as floating just in front of the screen, like `InfoPanel`'s HUD.
- Since a Full-SBS source is fused by the viewer into a single perceived image the size of *one* half, the real mouse's raw window-x is **clamped into `[0, halfWidth]`** before anything else uses it — past the midline, the cursor just pins at the right edge of the perceived image instead of jumping across it.
- Clicks are resolved against that same clamped, left-half-local position via `CursorHitRegistry`, **not** against wherever the real OS pointer physically is — so a click always matches what the cursor visually shows, even when the real mouse has strayed into the right half. This means every clickable overlay element (the settings-menu gear/switches/rows, `InfoPanel`'s favorite/warning/legend icons and align buttons) must additionally carry `Modifier.cursor3DClickTarget(onClick)`, which registers its real screen rect + callback into the registry. `Stereo3DCursorHost` consumes every raw pointer event at `PointerEventPass.Initial`, before it reaches the real (still-present, still duplicated-per-half) `clickable`/`Switch`/`Button` widgets — so those never fire natively anymore; the registry hit-test is the only path a click actually takes. One consequence: native press/ripple visual feedback on those widgets no longer animates, since they never see the down event.
- A registered element's `onClick` is read through a `SideEffect`-refreshed holder rather than the closure captured at registration time, so it can't go stale (e.g. keep referencing the previous photo's `file` after navigating away).
- Under "shrink controls" (`AppViewModel.shrinkControls`), the cursor dots also **match the depth of whatever panel/dialog they're hovering**, via `CursorDepthRegistry`/`Modifier.cursor3DDepthTarget(shiftPercent)` — otherwise a cursor always floating at the fixed -1% reads as detached from a control rendered at a different depth (e.g. the settings menu/`InfoPanel`'s own panel Box, which render at 0%/-1% respectively). **Whenever you add a new "shrink controls" panel/dialog (menu, `InfoPanel`-like HUD, `Stereo3DAlertDialog`-based dialog, or anything else wrapped in `shrinkHorizontally`), add `.cursor3DDepthTarget(theSameShiftPercentConstantThatPanelRendersAt)` to that panel's own outer container Box** (the one already carrying `.shrinkHorizontally(...)`), same left-half-only registration convention as `cursor3DClickTarget`. Falls back to the default -1% wherever the cursor isn't over any registered panel.

## Other notes:

- Packaged builds (MSI/EXE/MSIX) run on a jlink-trimmed JDK limited to the `modules(...)` list in `build.gradle.kts`; `run` uses the full JDK and hides missing ones. After adding a dependency or a new `java.*` API, re-run `./gradlew.bat suggestRuntimeModules` and update that list — otherwise the packaged app dies at startup with a bare "Failed to launch JVM". `main()` shows the real stack trace in a dialog and writes it to `~/sbs3dFullscreen-crash.log`.

- Window chrome is coupled to screen state: `undecorated` is true only in `ImageView`, and since AWT can't toggle `undecorated`
after the peer is created, the whole `Window` is wrapped in `key(undecorated)` to force recreation when it changes.
- Fullscreen is implemented as **undecorated + `WindowPlacement.Maximized`**, not `WindowPlacement.Fullscreen` — real OS exclusive fullscreen gets auto-minimized by Windows when the window loses focus (e.g. when a 3D monitor's own "activate 3D" popup steals focus), whereas Maximized just deactivates. See the comment in `Main.kt`'s `onFilesChosen` callback.
- Multi-image navigation (when multiple files are chosen) happens in `Main.kt` via `onPreviewKeyEvent` on the focused root `Box`, which calls into `AppViewModel`: Escape calls `closeImageView()`, Space/Right calls `showNextImage()`, Left calls `showPreviousImage()`.
- Launch-via-file-association: `main(args)` treats `args.firstOrNull()` as a file path; if present, `AppViewModel` is constructed already in `ImageView` with that file. This is what Windows passes when the app is invoked through the registered `.jpg`/`.jpeg` file association (configured in `build.gradle.kts` via `fileAssociation(...)`).
- Localization: uses Compose Multiplatform's `stringResource`/`compose.components.resources` (strings in `src/commonMain/composeResources/values{,-fr}/strings.xml`). Since Compose's resource system has no public API to override locale per-composition (only `Locale.getDefault()`), `LocalAppLocale` mutates the JVM default `Locale` on language switch and forces recomposition via `key(language)` in `Main.kt`.
- `commonMain`/`desktopMain` source set split exists for future KMP targets, but only `desktop` (JVM) is configured as a target; there's no Android/iOS/web target today.
- never backslash-escape apostrophes/quotes in this app's `strings.xml` files (`src/commonMain/composeResources/values{,-fr}/strings.xml`) — plain `'` is correct here. This applies to every string you touch, not just ones copied from the Camera 3D Android app: that app's `strings.xml` *does* require `\'` escaping, so when copying a string from there, strip the backslash; when writing a new string directly in this repo, never add one in the first place.
- when building UI create round icons and not rounded corner icons. And add a background icon
- for each prompt I give to you, start by rewording it in idiomatic English to help me improve my English and then execute the prompt
- never test the UI yourself (launching the app, screenshotting it, driving it) — build/compile to confirm it compiles, then let me run and test it myself
- when adding a new user-facing feature/action, propose whether it's worth a Google Analytics event (see `Analytics.kt`) before considering the feature done