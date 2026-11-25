# Vendored OpenCV 5 (Windows)

`org.opencv:opencv:5.1.0` (what CameraSync3D, the companion Android app, uses) is published as
an Android AAR only — there's no desktop-JVM artifact for it on Maven Central. To get real OpenCV 5
on this desktop app, download the official Windows build directly from opencv.org and vendor two
files here manually (this folder is gitignored-safe to keep local-only if you'd rather not commit
binaries — up to you):

1. Download the OpenCV **5.1.0** Windows release from https://opencv.org/releases/ (look for
   `opencv-5.1.0-windows.exe`, a self-extracting archive — no install, just extraction).
2. Extract it, then copy:
   - `opencv\build\java\opencv-510.jar` → `libs/opencv/opencv-510.jar`
   - `opencv\build\java\x64\opencv_java510.dll` → `libs/opencv/opencv_java510.dll`

   The exact filenames depend on how OpenCV's build encodes the version (5.1.0 → `510` by their
   usual convention) — check what's actually inside the extracted `java/` folder and adjust
   `build.gradle.kts`'s `opencvJar` path if it differs.
3. Re-run Gradle (`./gradlew.bat build`) — `build.gradle.kts` picks up the jar automatically once
   it exists at that path, and wires `-Djava.library.path` to this folder so the JVM finds the
   `.dll` at runtime.

**Note:** `src/desktopMain/kotlin/AutoAlign.kt` (the auto-align feature, bound to the `A` key in
`ImageScreen`) could not be compile-checked while writing it — no Maven-hosted OpenCV distribution
exposes OpenCV 5's `org.opencv.features`/`org.opencv.geometry` packages to verify against ahead of
time, and `Main.kt` calls into it directly (unconditionally), so **the whole app needs these two
files in place to build at all** from this point on. After vendoring them, run
`./gradlew.bat compileKotlinDesktop` — if `AutoAlign.kt` has errors, they're almost certainly a
mismatch between the assumed and real OpenCV 5 Java API (e.g. an overload name/signature), fixable
by adjusting that one file.
