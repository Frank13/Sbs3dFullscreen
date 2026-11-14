---
name: whats-new-release
description: Prepare a new SBS 3D Viewer release publication - first checks the Windows app is in sync with the Camera 3D Android app (stops and proposes a sync if not), proposes up to 3 "What's new" items from the git history, bumps appVersion, writes the EN/FR strings, and tells the user which Microsoft Store screenshots are now outdated and must be regenerated and re-uploaded. Use when the user wants to publish/release a new version or add a "What's new" entry.
---

Prepare the publication of a new release. Reword the user's prompt in idiomatic English first (project rule), then follow these steps.

## Files involved

- `gradle.properties` (`appVersion`)
- `src/desktopMain/kotlin/WhatsNew.kt` (`allWhatsNewReleases`)
- `src/commonMain/composeResources/values/strings.xml` and `values-fr/strings.xml`

## Steps

### 1. Check that the Windows app is in sync with the Android app (always first)

Before anything else, verify that every file shared with the Camera 3D Android app (`C:\Users\frank\Documents\AndroidStudioProjects\CameraSync3D`) is identical on both sides. **Do not run `tools/sync-from-android.ps1` here** - it copies files; this step only reads. Run this read-only check (it takes the file lists from the sync script itself, so it never goes stale):

```powershell
$android = "C:\Users\frank\Documents\AndroidStudioProjects\CameraSync3D"
$script  = Get-Content tools/sync-from-android.ps1 -Raw
$diffs = @()
# Verbatim-copied Kotlin files
foreach ($m in [regex]::Matches($script, 'Path = "([^"]+)"; SourceSet = "(\w+)"')) {
    $src = Join-Path "$android\app\src\main\java" $m.Groups[1].Value
    $dst = "src\$($m.Groups[2].Value)\kotlin\$($m.Groups[1].Value)"
    if (-not (Test-Path $src)) { $diffs += "MISSING ON ANDROID: $src"; continue }
    if (-not (Test-Path $dst)) { $diffs += "NOT YET SYNCED: $dst"; continue }
    if ((Get-FileHash $src).Hash -ne (Get-FileHash $dst).Hash) { $diffs += "DIFFERENT: $($m.Groups[1].Value)" }
}
# Drawables (compared after the same rewrite the sync script applies)
$block = [regex]::Match($script, '(?s)\$drawablesToSync = @\((.*?)\)').Groups[1].Value
foreach ($m in [regex]::Matches($block, '"([^"]+\.xml)"')) {
    $n = $m.Groups[1].Value
    $src = "$android\app\src\main\res\drawable\$n"; $dst = "src\commonMain\composeResources\drawable\$n"
    if (-not (Test-Path $src) -or -not (Test-Path $dst)) { $diffs += "MISSING drawable: $n"; continue }
    $expected = (Get-Content $src -Raw) -replace '\s+android:tint="[^"]*"', '' -replace '@android:color/white', '#FFFFFFFF'
    if ($expected -ne (Get-Content $dst -Raw)) { $diffs += "DIFFERENT drawable: $n" }
}
# New shared files on Android that the sync script doesn't list yet
Get-ChildItem "$android\app\src\main\java" -Recurse -Filter *.kt |
    Where-Object { (Select-String -Path $_.FullName -Pattern 'SHARED FILE' -SimpleMatch -Quiet) -and ($script -notmatch [regex]::Escape($_.Name)) } |
    ForEach-Object { $diffs += "SHARED FILE NOT IN SYNC SCRIPT: $($_.FullName)" }
if ($diffs) { $diffs } else { "IN SYNC" }
```

- If it prints `IN SYNC`, say so in one line and go on to step 2.
- Otherwise **stop the skill here**: list the differences to the user, explain that the release would ship code out of step with the Android app, and propose to sync first (run `./tools/sync-from-android.ps1`, fix the sync script's file list for any `SHARED FILE NOT IN SYNC SCRIPT` entry, then `./gradlew.bat compileKotlinDesktop`). Ask with one `AskUserQuestion` (header "Sync") offering "Sync with Android now" (recommended) and "Release without syncing". Only continue to step 2 if they explicitly choose to release without syncing; after a sync, tell them to re-run `/whats-new-release` once the build is green (the sync itself is a change to review and commit, and step 3's history must include it).
- A `DIFFERENT` file may also mean someone edited the Windows copy, which is forbidden (project rule) - point it out, never "fix" it by editing the Windows copy.

### 2. Version

Read `appVersion` in `gradle.properties` and the newest `versionName` in `allWhatsNewReleases`.
- If `appVersion` is already higher than the newest What's New entry, the bump was already done: propose to keep it.
- Otherwise suggest the next one (patch + 1, or minor + 1 for a feature release). `appVersion` must increase on every release build (jpackage only auto-uninstalls the old version then).

**The user must validate the version, every time**: use one `AskUserQuestion` (header "Version") showing the current `appVersion`, the newest What's New entry and the proposed version, with options "Keep <proposed>" (recommended), the alternative bump (patch vs minor), and the automatic "Other" to type their own. Never write `appVersion` or a What's New entry before this is answered.

### 3. Find what changed since the last version bump

List the commits that changed `appVersion`: `git log --format='%h %ad %s' --date=short -L'/^appVersion=/,+1:gradle.properties' -s`.
- Baseline = the **last version bump of an already-published release**. If the current `appVersion` has no entry in `allWhatsNewReleases` yet, its bump belongs to the release being prepared, so use the bump *before* it. Otherwise use the latest bump.
- Review `git log --oneline <baseline>..HEAD` and `git diff --stat <baseline>..HEAD`, and read the actual diffs of the user-visible files (screens, menus, strings) - commit messages alone are often vague or copy-pasted. Files under `fr/camera3d/camera` folders are synced from Android: count their user-visible effects, not their wording.

### 4. Propose up to 3 changes, from the user's perspective

Rank every change since the baseline by the **impact on the end user** (what they see or can now do), not by code size or effort. Criteria, in order: new capability they can use > visible improvement of an existing feature > fix of a crash/bug they could hit. Skip refactorings, dependency changes, tooling, Store/packaging work and anything invisible. Merge several commits about the same feature into one item.

**3 items is a maximum, not a target.** Only keep changes that are genuinely worth explaining to the user - never pad the list with weak items to reach 3. Then:
- If there are remaining user-visible changes that don't deserve their own line (minor fixes, small polish), the **last** item may be the generic `Bug fixes and small improvements` (FR: `Corrections de bugs et petites améliorations`). It counts toward the maximum of 3 and is never used more than once nor anywhere but last.
- A release can therefore be, e.g., 2 explained items + the generic one, 1 explained item, or just the generic item alone when nothing is worth explaining.
- If not even the generic item is justified (no user-visible change at all), tell the user and ask whether a release is really needed.

Write each explained item in user-friendly English, under ~60 characters, no technical jargon. Prefix a fix with `Fix:`. For each, add a one-line justification of why it made the list (shown to the user in the question, not stored); for the generic item, list the small changes it covers.

**The user validates each item individually, every time** - never ask for one global "OK", and never write an entry from unvalidated items. Use one `AskUserQuestion` call with one question per item (header "Item 1/2/3"), each offering: "Keep as is", "Replace with another change", "Reword" and "Remove this item" (the automatic "Other" lets them type their own text). For a replacement, propose another candidate from the history (or take their text) and have it validated the same way. Also let them add an item (up to 3 total) or swap explained items for the generic one. Their wording always wins. Repeat until every item is validated.

### 5. Translate to French

Translate the confirmed items (translate "Playlist" as "Diaporama"; fixes start with `Correction :`; the generic item is `Corrections de bugs et petites améliorations`). Show them and have the user validate each translation (same per-item `AskUserQuestion` as step 4) before writing.

### 6. Write the entry (add only, never modify existing entries)

- `gradle.properties`: update `appVersion` if needed.
- `WhatsNew.kt`: insert a new `WhatsNewRelease(versionName = "X.Y.Z", items = listOf(Res.string.whats_new_vXYZ_item_1, ...))` at the **top** of `allWhatsNewReleases`, plus the matching `import sbs3dViewer.resources.whats_new_vXYZ_item_N` lines (keys: version without dots, e.g. `1.0.5` -> `v105`).
- Both `strings.xml`: insert before `</resources>`, under a `<!-- vX.Y.Z -->` comment. Never backslash-escape apostrophes in these files.
- `versionName` in `WhatsNew.kt` must equal `appVersion` exactly, otherwise the dialog is never shown.

### 7. Store screenshots check

The Microsoft Store listing screenshots are generated by `StoreScreenshotTest` (`./gradlew.bat desktopTest --tests StoreScreenshotTest`, output in `build/store-screenshots/`, EN + FR). Mapping:

| Screenshot | Rendered by | Source files to watch |
|---|---|---|
| `welcome_{en,fr}` | `WelcomeScreen` | `WelcomeScreen.kt`, `AppTheme.kt` |
| `gallery_{en,fr}` | `GalleryScreen` | `GalleryScreen.kt`, `GalleryState.kt` |
| `image_view_menu_{en,fr}` | `ImageScreen` (shrunk controls, info panel) | `ImageScreen.kt`, `InfoPanel.kt`, `Cursor3D.kt`, `ShrinkControls.kt`, `Exif3d.kt` |
| `image_view_info_panel_{en,fr}` | `ImageScreen` (settings menu open) | `ImageScreen.kt`, `SettingsMenu.kt`, `Cursor3D.kt` |
| `playlist_edit_{en,fr}` | `PlaylistScreen` | `PlaylistScreen.kt`, `PlaylistItemRow.kt`, `PlaylistFieldsComposables.kt`, `PortablePlaylistUiAtoms.kt` |

Run `git diff --stat <last-release-commit>..HEAD` on these files (and the strings they display in `strings.xml`), and report to the user, per screenshot, **whether it is outdated** with the reason (e.g. "image_view_info_panel: the settings menu gained 2 rows"). Also flag:
- a new screen or feature worth its own screenshot (then add a `@Test` that calls `captureScreenshot(...)` in `StoreScreenshotTest.kt` - never call `runDesktopComposeUiTest` directly);
- a screenshot that shows removed or renamed UI.

Finish this step with an explicit list: **"Screenshots to update on the Store: ..."** (or "none - all current screenshots are still accurate").

If any screenshot is outdated, **always ask the user** (one `AskUserQuestion`, header "Screenshots") whether to regenerate them now with `StoreScreenshotTest` (needs `sample_images/`) - never skip this question silently. If they accept, run it, check it passed, and list the regenerated files in `build/store-screenshots/` with their timestamps. Do not run the app itself, and uploading to Partner Center is always the user's job.

### 8. Wrap-up

- Run `./gradlew.bat compileKotlinDesktop compileTestKotlinDesktop` to confirm both the app and the tests compile (a screen signature change silently breaks `StoreScreenshotTest` otherwise).
- Check the open-source license list in `AboutScreen.kt` against `./gradlew.bat dependencies --configuration desktopRuntimeClasspath`: any new library must be listed with its license. Never add GPL/AGPL dependencies (closed-source / Store distribution).
- Propose whether the new features deserve a Google Analytics event (`Analytics.kt`) - project rule.
- Give a one-line summary per file changed, then the release commands: `./gradlew.bat packageMsix` (Store) and/or `packageMsi`/`packageExe`.

## Rules

- Never edit existing release entries or strings, only add.
- Always start with the Android sync check (step 1); stop and propose a sync if it fails.
- 1 to 3 items per release (3 is a maximum), each under ~60 characters; the optional last item may be the generic "Bug fixes and small improvements".
- Nothing is committed or published unless the user asks.
