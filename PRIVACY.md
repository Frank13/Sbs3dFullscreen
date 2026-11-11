# Privacy Policy — SBS 3D Viewer

_Last updated: 2026-11-08_

SBS 3D Viewer ("the app") is a Windows desktop viewer for side-by-side 3D JPEG photos. This
page describes what data the app collects and how it is used.

## Your photos and videos

The app opens image and video files you choose, either by double-clicking them in Windows
(file association) or by picking them from the in-app file/playlist dialogs. Files are read and
processed entirely on your device. The app never uploads, transmits, or shares the contents of
your photos or videos, and does not have access to them beyond what is needed to display, edit
(crop/align/spot-fix), or export them locally when you ask it to.

The "Share" feature (email / save to Downloads) only acts when you explicitly trigger it, and
only sends the specific file(s) you chose, to the destination you chose (your own email client
or local Downloads folder) — the app itself does not receive or relay that content anywhere.

## Usage analytics

The app can send anonymous usage events (e.g. "app launched," "playlist created," "crop saved,"
which menu item was clicked) to Google Analytics, to help understand which features are used.

- **Analytics is off by default.** The first time the app needs to send an event, it asks for
  your consent via an in-app dialog. If you decline, no analytics data is ever sent, and no
  further prompt is shown.
- Events are tied to a random identifier generated on your device (not your name, email, or
  Windows account), stored locally so repeat launches are recognized as the same install.
- Event data is limited to feature/interaction names and small technical parameters (e.g. which
  menu item, which screen, a count, a success/failure flag). Filenames, file paths, and file
  contents are never included.
- Google's infrastructure processes standard web request metadata (such as IP address) as part
  of receiving these events, under [Google's Privacy Policy](https://policies.google.com/privacy).
- You can change your mind at any time from the app's settings.

## Crash and error reports

With the same consent as usage analytics (and never without it), the app sends a report to
[Sentry](https://sentry.io) when it hits an error or crashes, to help find and fix bugs.

- A report contains the error type and message, the technical stack trace (which parts of the
  app's code were running), the app version, and your Windows and Java versions.
- Before a report leaves your device, file paths, file names, your Windows user folder and your
  Windows user name are removed from it. Your computer's name, IP address and photo/video
  contents are never included.
- Reports are stored in Sentry's European Union data region (Frankfurt, Germany) and are deleted
  automatically after Sentry's retention period (currently 30 days), under
  [Sentry's Privacy Policy](https://sentry.io/privacy/).
- Every crash is also written to a local log file in your Windows user folder
  (`sbs3dViewer-crash.log`), which never leaves your device unless you choose to share it.

## Data retention and deletion

Locally stored data (your consent choice, the random install identifier and the local crash log)
lives on your device; the consent choice and install identifier are in the standard Windows
per-user application preferences store and are removed if you uninstall the app. To request
deletion of any analytics data or crash reports already sent, contact us using the details below.

## Contact

Questions about this policy can be sent to: camera3d@gmail.com

## Changes to this policy

This policy may be updated as the app's features change. The "Last updated" date above reflects
the most recent revision.
