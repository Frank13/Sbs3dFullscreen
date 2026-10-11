# Signs build/msix/*.msix with a local self-signed test certificate and (optionally) installs it, so the
# package can be sideloaded without going through the Microsoft Store. Run ./gradlew.bat packageMsix first.
#
#   ./tools/sign-msix-for-sideload.ps1            # create/reuse cert, trust it, sign the newest .msix
#   ./tools/sign-msix-for-sideload.ps1 -Install   # ... and Add-AppxPackage it afterwards
#
# Trusting the certificate (LocalMachine\TrustedPeople) needs an elevated PowerShell; the first run will
# stop with a message if it isn't elevated. Do NOT upload the signed package to Partner Center - upload
# the unsigned one from packageMsix (the Store signs it itself).

param([switch]$Install)

$ErrorActionPreference = "Stop"
$root = Join-Path $PSScriptRoot ".."

# The cert subject must match the manifest's Publisher exactly, so read it from gradle.properties.
$publisherLine = Select-String -Path (Join-Path $root "gradle.properties") -Pattern '^msixPublisher=(.+)$' | Select-Object -First 1
if (-not $publisherLine) { throw "msixPublisher not found in gradle.properties" }
$subject = $publisherLine.Matches[0].Groups[1].Value.Trim()

$msix = Get-ChildItem (Join-Path $root "build\msix") -Filter *.msix -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $msix) { throw "No .msix in build\msix - run ./gradlew.bat packageMsix first." }

$signtool = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -like "*\x64\*" } | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $signtool) { throw "signtool.exe not found - install the Windows 10/11 SDK." }

$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Subject -eq $subject -and $_.FriendlyName -eq "SBS3D sideload test" } |
    Sort-Object NotAfter -Descending | Select-Object -First 1
if (-not $cert) {
    Write-Host "Creating self-signed certificate for $subject"
    $cert = New-SelfSignedCertificate -Type Custom -Subject $subject -KeyUsage DigitalSignature `
        -FriendlyName "SBS3D sideload test" -CertStoreLocation "Cert:\CurrentUser\My" -NotAfter (Get-Date).AddYears(3) `
        -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3", "2.5.29.19={text}")
}

$trusted = Get-ChildItem Cert:\LocalMachine\TrustedPeople | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }
if (-not $trusted) {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) { throw "Certificate isn't trusted yet - re-run this script once from an elevated PowerShell." }
    $cer = Join-Path $env:TEMP "sbs3d-sideload.cer"
    Export-Certificate -Cert $cert -FilePath $cer | Out-Null
    Import-Certificate -FilePath $cer -CertStoreLocation Cert:\LocalMachine\TrustedPeople | Out-Null
    Write-Host "Certificate added to LocalMachine\TrustedPeople"
}

# Sign a copy so the unsigned Store-bound package stays untouched.
$signed = Join-Path $msix.DirectoryName ($msix.BaseName + "-sideload.msix")
Copy-Item $msix.FullName $signed -Force
& $signtool.FullName sign /fd SHA256 /sha1 $cert.Thumbprint $signed
if ($LASTEXITCODE -ne 0) { throw "signtool failed with exit code $LASTEXITCODE" }
Write-Host "Signed package: $signed"

if ($Install) {
    Add-AppxPackage -Path $signed
    Write-Host "Installed."
}
