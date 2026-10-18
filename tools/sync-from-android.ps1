<#
.SYNOPSIS
    Copies the portable (Android-free) source files shared with CameraSync3D into this repo.

.DESCRIPTION
    CameraSync3D (the companion Android app) is the source of truth for these files: the EXIF3D
    metadata format, EXIF formatting helpers, the playlist YAML format, the Playlist/PlaylistItem
    domain model + storage abstraction, and the OpenCV auto-align algorithm core. Each is kept free
    of android.* imports on the Android side specifically so it can be copied here verbatim.

    Run this manually after changing any of those files in CameraSync3D. It is a plain file copy
    (no text rewriting) - both copies must declare the same package, so the destination mirrors the
    source's package-relative path.

    Also warns if these have drifted between the two projects:
      - Jackson (tools.jackson.*): Desc3d.kt/YamlPlaylistFormat.kt are only wire-compatible
        between apps if both sides serialize/deserialize with the same version.
      - OpenCV: AutoAlignCore.kt calls OpenCV 5-specific packages (org.opencv.features,
        org.opencv.geometry); a version drift could mean mismatched APIs or algorithm behavior
        between the two apps' auto-align results.
      - Kotlin: the synced .kt files must compile under both projects' Kotlin toolchains.
      - autoalign2d.kt / autoalignByHomography.kt: these can't be synced verbatim (Android-Bitmap
        saturated), so desktop's AutoAlign.kt hand-ports their algorithms instead. This script
        hashes the live Android files and compares them against SOURCE_HASH comments recorded in
        AutoAlign.kt, warning if the Android algorithm changed since the last hand-port.

.PARAMETER AndroidProjectPath
    Path to the CameraSync3D checkout. Defaults to the path used throughout this port.
#>
param(
    [string]$AndroidProjectPath = "C:\Users\frank\Documents\AndroidStudioProjects\CameraSync3D"
)

$ErrorActionPreference = "Stop"

$androidSrc = Join-Path $AndroidProjectPath "app\src\main\java"
$repoRoot = Split-Path -Parent $PSScriptRoot

# Each entry: source path (relative to $androidSrc), destination source set (commonMain/desktopMain).
# Destination path always mirrors the source's package-relative path under that source set.
# All of these are grouped under commonMain - even AutoAlignCore.kt, whose org.opencv.* types are
# only available once OpenCV is vendored (see build.gradle.kts's commonMain dependencies block) -
# so every file synced from CameraSync3D lives in one place rather than being split across source
# sets by which platform types it happens to need. desktopMain is reserved for genuinely
# desktop-only code (video playback, EXIF I/O, ...) that has no Android counterpart to sync from.
$filesToSync = @(
    @{ Path = "fr\camera3d\camera\shared\Desc3d.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\shared\ExifFormatting.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\shared\FilenameIncrement.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\shared\SideBySideLikeliness.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\service\yamlImporter\YamlPlaylistFormat.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\domain\Playlist.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\domain\PlaylistItem.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\domain\PlaylistStorage.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\domain\TextStyleConfig.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_edit\autoalign\AutoAlignCore.kt"; SourceSet = "commonMain" }
    # Portable Compose UI shared with the playlist editor screen (PlaylistScreen.kt on desktop /
    # PlaylistFragment.kt on Android). See PortablePlaylistUiAtoms.kt's header comment for why these
    # are separate from (not modifications of) the widely-used originals in the same directories.
    @{ Path = "fr\camera3d\camera\common\ui_components\PortablePlaylistUiAtoms.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\common\ui_components\ScreenWith3dotMenuAndSnackbar.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\common\ui_components\AnimatedSnackbarHost.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\common\ui_components\snackbar.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\PlaylistScreenStrings.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\PlaylistFieldsComposables.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\PlaylistItemRow.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\PlaylistItemScreenStrings.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\PlaylistItemFieldsComposables.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\PortableSlideshowSlides.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\PortableFullscreenScroll.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\StyledPositionedText.kt"; SourceSet = "commonMain" }
    @{ Path = "fr\camera3d\camera\feature_playlists\ui\ComposableTextStyleEditor.kt"; SourceSet = "commonMain" }
)

# Vector drawables backing the Exif3d info panel's icons (3D characteristic glyph, legend bubbles).
# Unlike $filesToSync above, these are NOT copied verbatim: Android vector XML allows two things
# Compose Multiplatform's resource system can't resolve at runtime (throws IllegalArgumentException
# from painterResource) - android:tint on the root <vector> and @android:color/* references on
# <path android:fillColor=...> - so both are rewritten below before being written to destination.
# Tint is dropped (the desktop Icon() composables tint these themselves) and @android:color/white
# becomes a literal #FFFFFFFF. Re-run this script after adding/changing any of these in CameraSync3D;
# a source path with attributes this rewrite doesn't know about fails loudly instead of shipping
# a drawable that crashes painterResource() at runtime.
$drawablesToSync = @(
    "ic_add_comment.xml"
    "ic_text_comment.xml"
    "ic_image_comment.xml"
    "outline_3d_24.xml"
)

if (-not (Test-Path $androidSrc)) {
    throw "CameraSync3D source root not found at '$androidSrc'. Pass -AndroidProjectPath if it's elsewhere."
}

# --- Forbidden-import check ---
# Compose Multiplatform mirrors AndroidX Compose's package names exactly (androidx.compose.animation.*,
# androidx.compose.foundation.*, androidx.compose.material3.*, androidx.compose.material.icons.*,
# androidx.compose.runtime.*, androidx.compose.ui.*), which is what makes syncing Compose UI files at
# all possible - so those import prefixes are allowed. Two androidx.compose.ui subpackages are
# Android-resource-only and stay forbidden despite the prefix match: androidx.compose.ui.res
# (stringResource(Int)/colorResource(Int)/painterResource(Int), which resolve Android R ids - Compose
# Multiplatform's desktop equivalent is org.jetbrains.compose.resources.stringResource(StringResource),
# a different API) and androidx.compose.ui.tooling (only Android Studio actually renders @Preview).
# Anything under bare android.* or any other androidx.* (hilt, navigation, activity, fragment,
# lifecycle, documentfile, core, ...) stays forbidden as before.
function Test-ForbiddenImportLine([string]$line) {
    if ($line -notmatch '^\s*import\s+(android\.|androidx\.)') { return $false }
    if ($line -match '^\s*import\s+android\.') { return $true }
    if ($line -match '^\s*import\s+androidx\.compose\.ui\.res(\.|$)') { return $true }
    if ($line -match '^\s*import\s+androidx\.compose\.ui\.tooling(\.|$)') { return $true }
    if ($line -match '^\s*import\s+androidx\.compose\.(animation|foundation|material3|material\.icons|runtime|ui)(\.|$)') { return $false }
    return $true
}

# --- Jackson version check ---
# CameraSync3D declares its dependencies through the Gradle version catalog
# (gradle\libs.versions.toml) rather than as inline "group:name:version" strings, so resolve
# "group:name" -> version from there.
function Get-AndroidCatalogLibraries([string]$projectPath) {
    $libs = @{}
    $tomlPath = Join-Path $projectPath "gradle\libs.versions.toml"
    if (-not (Test-Path $tomlPath)) { return $libs }
    $content = Get-Content -Path $tomlPath -Raw
    $versionRefs = @{}
    foreach ($m in [regex]::Matches($content, '(?m)^\s*([\w.-]+)\s*=\s*"([^"]+)"\s*$')) {
        $versionRefs[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    foreach ($m in [regex]::Matches($content, '(?m)^\s*[\w.-]+\s*=\s*\{[^}]*group\s*=\s*"([^"]+)"[^}]*name\s*=\s*"([^"]+)"[^}]*version\.ref\s*=\s*"([^"]+)"[^}]*\}')) {
        $ref = $m.Groups[3].Value
        if ($versionRefs.ContainsKey($ref)) { $libs["$($m.Groups[1].Value):$($m.Groups[2].Value)"] = $versionRefs[$ref] }
    }
    return $libs
}
$androidCatalog = Get-AndroidCatalogLibraries $AndroidProjectPath

# Desc3d.kt/YamlPlaylistFormat.kt are only wire-compatible between the two apps if both serialize
# with the same Jackson version, so a drift here is worth flagging even though it can't break the
# sync/copy itself.
function Get-JacksonVersions([string]$buildFilePath) {
    $versions = @{}
    if (-not (Test-Path $buildFilePath)) { return $versions }
    $content = Get-Content -Path $buildFilePath -Raw
    foreach ($m in [regex]::Matches($content, '(tools\.jackson\.[\w.]+:[\w-]+):(\d+(?:\.\d+)+)')) {
        $versions[$m.Groups[1].Value] = $m.Groups[2].Value
    }
    return $versions
}

$androidBuildFile = Join-Path $AndroidProjectPath "app\build.gradle"
$desktopBuildFile = Join-Path $repoRoot "build.gradle.kts"
$androidJackson = Get-JacksonVersions $androidBuildFile
foreach ($key in $androidCatalog.Keys) {
    if ($key -like 'tools.jackson.*') { $androidJackson[$key] = $androidCatalog[$key] }
}
$desktopJackson = Get-JacksonVersions $desktopBuildFile

$jacksonCoords = @($androidJackson.Keys) + @($desktopJackson.Keys) | Select-Object -Unique
if ($jacksonCoords.Count -eq 0) {
    Write-Warning "Could not find any tools.jackson.* dependency declarations in either build file - skipping version check."
}
foreach ($coord in $jacksonCoords) {
    $androidVersion = $androidJackson[$coord]
    $desktopVersion = $desktopJackson[$coord]
    if ($androidVersion -and $desktopVersion -and $androidVersion -ne $desktopVersion) {
        Write-Warning "Jackson version mismatch for ${coord}: CameraSync3D uses $androidVersion, sbs3Dfullscreen uses $desktopVersion. Update build.gradle.kts to match - otherwise EXIF3D/playlist YAML data written by one app may not round-trip through the other."
    } elseif ($androidVersion -and -not $desktopVersion) {
        Write-Warning "${coord} ($androidVersion) is declared in CameraSync3D but not in sbs3Dfullscreen's build.gradle.kts."
    } elseif ($desktopVersion -and -not $androidVersion) {
        Write-Warning "${coord} ($desktopVersion) is declared in sbs3Dfullscreen but not found in CameraSync3D's build.gradle."
    } else {
        Write-Host "Jackson version OK: $coord = $androidVersion"
    }
}

# --- OpenCV version check ---
# Android declares a real Maven version; desktop vendors a local jar (no Maven coordinate to
# read), so this compares Android's declared version against the vendored jar's filename, which
# encodes the version by OpenCV's own convention (5.0.0 -> opencv-500.jar - see libs/opencv/README.md).
function Get-AndroidOpenCvVersion([string]$buildFilePath) {
    if (-not (Test-Path $buildFilePath)) { return $null }
    $content = Get-Content -Path $buildFilePath -Raw
    $m = [regex]::Match($content, 'org\.opencv:opencv:(\d+)\.(\d+)\.(\d+)(?:\.\d+)?')
    if (-not $m.Success) { return $null }
    return [PSCustomObject]@{
        Full  = $m.Value.Substring($m.Value.LastIndexOf(':') + 1)
        Major = $m.Groups[1].Value
        Minor = $m.Groups[2].Value
        Patch = $m.Groups[3].Value
    }
}

$androidOpenCv = Get-AndroidOpenCvVersion (Join-Path $AndroidProjectPath "app\build.gradle")
if (-not $androidOpenCv -and $androidCatalog.ContainsKey('org.opencv:opencv')) {
    $cv = [regex]::Match($androidCatalog['org.opencv:opencv'], '^(\d+)\.(\d+)\.(\d+)')
    if ($cv.Success) {
        $androidOpenCv = [PSCustomObject]@{ Full = $androidCatalog['org.opencv:opencv']; Major = $cv.Groups[1].Value; Minor = $cv.Groups[2].Value; Patch = $cv.Groups[3].Value }
    }
}
$opencvDir = Join-Path $repoRoot "libs\opencv"
if (-not $androidOpenCv) {
    Write-Warning "Could not find an org.opencv:opencv:<version> declaration in CameraSync3D's app\build.gradle or gradle\libs.versions.toml - skipping OpenCV version check."
} else {
    $expectedJarName = "opencv-$($androidOpenCv.Major)$($androidOpenCv.Minor)$($androidOpenCv.Patch).jar"
    $expectedJarPath = Join-Path $opencvDir $expectedJarName
    $existingJars = if (Test-Path $opencvDir) { Get-ChildItem $opencvDir -Filter "opencv-*.jar" } else { @() }

    if (Test-Path $expectedJarPath) {
        Write-Host "OpenCV version OK: CameraSync3D uses $($androidOpenCv.Full), found matching $expectedJarName"
    } elseif ($existingJars.Count -gt 0) {
        Write-Warning "OpenCV version mismatch: CameraSync3D uses $($androidOpenCv.Full) (expects $expectedJarName), but libs\opencv\ has $(($existingJars.Name) -join ', ') instead. Re-vendor the matching OpenCV Windows build - see libs/opencv/README.md."
    } else {
        Write-Host "OpenCV not vendored yet ($expectedJarName expected once you do - see libs/opencv/README.md)."
    }
}

# --- Kotlin version check ---
function Get-AndroidKotlinVersion([string]$rootBuildFilePath) {
    if (-not (Test-Path $rootBuildFilePath)) { return $null }
    $content = Get-Content -Path $rootBuildFilePath -Raw
    $m = [regex]::Match($content, "kotlin_version\s*=\s*['""]([\d.]+)['""]")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function Get-DesktopKotlinVersion([string]$buildFileKtsPath) {
    if (-not (Test-Path $buildFileKtsPath)) { return $null }
    $content = Get-Content -Path $buildFileKtsPath -Raw
    $m = [regex]::Match($content, 'kotlin\("multiplatform"\)\s*version\s*"([\d.]+)"')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

$androidKotlin = Get-AndroidKotlinVersion (Join-Path $AndroidProjectPath "build.gradle")
$desktopKotlin = Get-DesktopKotlinVersion $desktopBuildFile
if (-not $androidKotlin -or -not $desktopKotlin) {
    Write-Warning "Could not determine Kotlin version for one or both projects - skipping Kotlin version check."
} elseif ($androidKotlin -ne $desktopKotlin) {
    Write-Warning "Kotlin version mismatch: CameraSync3D uses $androidKotlin, sbs3Dfullscreen uses $desktopKotlin. The synced files should still compile across adjacent versions, but keeping them aligned avoids subtle language/stdlib differences."
} else {
    Write-Host "Kotlin version OK: $androidKotlin"
}

# --- Hand-ported (non-syncable) auto-align files drift check ---
# autoalign2d.kt and autoalignByHomography.kt can't be synced verbatim (see $filesToSync's
# comment): they're saturated with android.graphics.Bitmap / org.opencv.android.Utils calls that
# don't exist on desktop JVM, so the forbidden-import check below would refuse them anyway.
# Instead, AutoAlign.kt's alignAndCrop/alignAndCropHomography are hand-ported copies of their
# algorithms (matcher choice, RANSAC/homography estimation, crop-rect math), recorded via a
# SOURCE_HASH comment in AutoAlign.kt at the time they were last hand-updated. This compares that
# recorded hash against the live Android file so a future algorithm change there doesn't silently
# drift out of sync on desktop.
$filesToWarnIfChanged = @(
    @{ Path = "fr\camera3d\camera\feature_edit\autoalign\autoalign2d.kt"; SourceHashName = "autoalign2d.kt" }
    @{ Path = "fr\camera3d\camera\feature_edit\autoalign\autoalignByHomography.kt"; SourceHashName = "autoalignByHomography.kt" }
)
$autoAlignKtPath = Join-Path $repoRoot "src\desktopMain\kotlin\AutoAlign.kt"
$autoAlignKtContent = if (Test-Path $autoAlignKtPath) { Get-Content -Path $autoAlignKtPath -Raw } else { $null }

foreach ($entry in $filesToWarnIfChanged) {
    $sourcePath = Join-Path $androidSrc $entry.Path
    if (-not (Test-Path $sourcePath)) {
        Write-Warning "Skipping drift check for missing source file: $sourcePath"
        continue
    }
    if (-not $autoAlignKtContent) {
        Write-Warning "Cannot check drift for $($entry.SourceHashName): AutoAlign.kt not found at $autoAlignKtPath"
        continue
    }
    $recordedMatch = [regex]::Match($autoAlignKtContent, "SOURCE_HASH $([regex]::Escape($entry.SourceHashName)):\s*([0-9A-Fa-f]{64})")
    if (-not $recordedMatch.Success) {
        Write-Warning "AutoAlign.kt has no 'SOURCE_HASH $($entry.SourceHashName): <sha256>' comment to check drift against."
        continue
    }
    $recordedHash = $recordedMatch.Groups[1].Value
    $liveHash = (Get-FileHash -Path $sourcePath -Algorithm SHA256).Hash
    if ($liveHash -ieq $recordedHash) {
        Write-Host "Drift check OK: $($entry.SourceHashName) unchanged since AutoAlign.kt was last hand-updated."
    } else {
        Write-Warning "$($entry.SourceHashName) has changed in CameraSync3D since AutoAlign.kt was last hand-updated (source hash mismatch). Review the diff in $sourcePath and update AutoAlign.kt's alignAndCrop/alignAndCropHomography by hand to match, then refresh its SOURCE_HASH comment to $liveHash."
    }
}

$copied = 0
foreach ($entry in $filesToSync) {
    $sourcePath = Join-Path $androidSrc $entry.Path
    $destPath = Join-Path $repoRoot "src\$($entry.SourceSet)\kotlin\$($entry.Path)"

    if (-not (Test-Path $sourcePath)) {
        Write-Warning "Skipping missing source file: $sourcePath"
        continue
    }

    # Fail loudly rather than silently sync a file that picked up an Android-only import -
    # these files are shared verbatim, so this would break the desktop build.
    $forbiddenImports = Get-Content -Path $sourcePath | Where-Object { Test-ForbiddenImportLine $_ }
    if ($forbiddenImports) {
        Write-Error "Refusing to sync $($entry.Path): contains Android-only imports:`n$($forbiddenImports -join "`n")"
        continue
    }

    New-Item -ItemType Directory -Force -Path (Split-Path $destPath) | Out-Null
    Copy-Item -Path $sourcePath -Destination $destPath -Force
    Write-Host "Synced $($entry.Path) -> src\$($entry.SourceSet)\kotlin\$($entry.Path)"
    $copied++
}

Write-Host "`nDone: $copied/$($filesToSync.Count) files synced."

# --- Drawable sync (rewritten, not verbatim - see $drawablesToSync's comment above) ---
$androidDrawableDir = Join-Path $AndroidProjectPath "app\src\main\res\drawable"
$destDrawableDir = Join-Path $repoRoot "src\commonMain\composeResources\drawable"

$drawablesCopied = 0
foreach ($name in $drawablesToSync) {
    $sourcePath = Join-Path $androidDrawableDir $name
    $destPath = Join-Path $destDrawableDir $name

    if (-not (Test-Path $sourcePath)) {
        Write-Warning "Skipping missing drawable: $sourcePath"
        continue
    }

    $content = Get-Content -Path $sourcePath -Raw
    $rewritten = $content -replace '\s+android:tint="[^"]*"', '' -replace '@android:color/white', '#FFFFFFFF'

    # Any other @android:... or @color/... reference has no known-safe literal to substitute -
    # fail loudly rather than ship a drawable that will throw at painterResource() runtime.
    if ($rewritten -match '@(android:)?color/') {
        Write-Error "Refusing to sync $name`: contains an unhandled color reference this script doesn't know how to rewrite (only @android:color/white -> #FFFFFFFF is handled)."
        continue
    }

    New-Item -ItemType Directory -Force -Path $destDrawableDir | Out-Null
    Set-Content -Path $destPath -Value $rewritten -NoNewline
    Write-Host "Synced (rewritten) $name -> src\commonMain\composeResources\drawable\$name"
    $drawablesCopied++
}

Write-Host "Done: $drawablesCopied/$($drawablesToSync.Count) drawables synced."
