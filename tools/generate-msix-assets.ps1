# Derives the PNG Store logo assets AppxManifest.xml references (packaging/msix/Assets/) from
# src/commonMain/composeResources/drawable/icon.png (the 256x256 master the app icon is drawn at -
# much cleaner than downscaling icons/icon.ico). icon.png already carries its own rounded-square
# background and safe-zone padding, so each tile is just a high-quality edge-to-edge resize of it
# (or of the square-cornered icons/icon-square.png for -Square tiles, see below): wrapping it in an
# extra badge would shrink the glyph and make it unreadable at taskbar sizes.
# Re-run whenever generate-app-icon.py has been re-run.

Add-Type -AssemblyName System.Drawing

$sourcePath = Join-Path $PSScriptRoot "..\src\commonMain\composeResources\drawable\icon.png"
$squareSourcePath = Join-Path $PSScriptRoot "..\icons\icon-square.png"
$outDir = Join-Path $PSScriptRoot "..\packaging\msix\Assets"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$source = [System.Drawing.Image]::FromFile($sourcePath)
# The Store (and Start, for the 150x150 tile) applies its own corner mask, and paints transparent
# pixels with the manifest's BackgroundColor or its own dark backdrop - so icon.png's transparent
# rounded corners showed up there as black wedges. -Square tiles use this full-bleed variant instead.
$squareSource = [System.Drawing.Image]::FromFile($squareSourcePath)

function New-Tile([string]$destName, [int]$size, [switch]$Square) {
    $tileSource = if ($Square) { $squareSource } else { $source }
    $canvas = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    # TileFlipXY wrap mode avoids the faint semi-transparent fringe GDI+ otherwise bleeds in along
    # the edges when downscaling.
    $attributes = New-Object System.Drawing.Imaging.ImageAttributes
    $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
    $destRect = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $g.DrawImage($tileSource, $destRect, 0, 0, $tileSource.Width, $tileSource.Height, [System.Drawing.GraphicsUnit]::Pixel, $attributes)
    $attributes.Dispose()

    $g.Dispose()
    $destPath = Join-Path $outDir $destName
    $canvas.Save($destPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $canvas.Dispose()
    Write-Host "Wrote $destPath"
}

# Square44x44Logo is the taskbar / Start app-list icon: keep its rounded, transparent-cornered shape.
New-Tile "Square44x44Logo.png" 44
New-Tile "Square150x150Logo.png" 150 -Square
New-Tile "StoreLogo.png" 50 -Square

# Not part of the MSIX package itself (kept out of Assets/ so packageMsix's directory copy
# doesn't bundle it in) - Partner Center's "Store listings" page separately asks for its own
# 300x300 app icon upload. Same full-size rendering as the package tiles above; upload
# packaging/msix/StoreListing/StoreListing300x300.png there by hand.
$listingDir = Join-Path $PSScriptRoot "..\packaging\msix\StoreListing"
New-Item -ItemType Directory -Force -Path $listingDir | Out-Null
$outDir = $listingDir
New-Tile "StoreListing300x300.png" 300 -Square

$squareSource.Dispose()
$source.Dispose()
