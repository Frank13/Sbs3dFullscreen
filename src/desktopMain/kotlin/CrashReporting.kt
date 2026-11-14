import io.sentry.KeyValueCollectionBehavior
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File

/**
 * Crash/error reporting to Sentry (there is no Crashlytics SDK for desktop JVM apps), plus a
 * local ~/sbs3dViewer-crash.log for every caught crash. Gated on the same consent as
 * Analytics.kt - the consent dialog already covers "errors encountered" - and nothing is ever
 * sent when the DSN isn't configured (same local.properties mechanism as GA4's credentials).
 *
 * Three entry points catch what would otherwise kill the app or a window:
 * - [install]'s default uncaught-exception handler: any thread (VLC/JNA callbacks, AWT, ...).
 * - [coroutineHandler]: added to this app's long-lived background CoroutineScopes.
 * - Main.kt's LocalWindowExceptionHandlerFactory: Compose composition/layout/draw/input errors,
 *   which it recovers from by resetting to the Welcome screen in a fresh window.
 */
object CrashReporting {
    private val dsn = System.getProperty("sentry.dsn").orEmpty()

    // The user profile root, which MSIX doesn't virtualize unlike AppData.
    private val crashLogFile = File(System.getProperty("user.home"), "sbs3dViewer-crash.log")
    private const val MaxCrashLogBytes = 1_000_000L

    private val userHome = System.getProperty("user.home").orEmpty()

    // Absolute Windows paths (drive-letter, incl. file:/C:/... URIs, and UNC) up to the end of the
    // line or a quote: the consent dialog promises no filenames are sent, and exception messages
    // (FileNotFoundException, ImageIO, ...) routinely embed the full path of the photo involved.
    private val pathRegex = Regex("""[A-Za-z]:[\\/][^\r\n"'<>|?*]*|\\\\[^\s\\]+\\[^\r\n"'<>|?*]*""")

    private val userName = System.getProperty("user.name").orEmpty()

    // Bare filenames of the media/playlist files this app handles ("Cannot decode IMG_1234.jpg"),
    // which carry no drive letter for pathRegex to catch. Limited to these extensions so class
    // names like java.lang.String in exception messages are left readable.
    private val fileNameRegex = Regex(
        """[^\s\\/:"'<>|?*]+\.(?:${(PhotoExtensions + VideoExtensions + setOf("png", "json", "zip")).joinToString("|")})\b""",
        RegexOption.IGNORE_CASE,
    )

    @Volatile
    private var started = false

    /** Called first thing in main(): installs the global handler and starts Sentry if allowed. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            report(throwable, source = "thread:${thread.name}")
            // A background thread dying doesn't take the JVM down, so there's nothing else to
            // recover here - just keep whatever handler was installed before (if any) informed.
            previous?.uncaughtException(thread, throwable)
        }
        if (Analytics.consentGranted) start()
    }

    /** Mirrors Analytics.setConsent, so answering the consent dialog takes effect immediately. */
    fun onConsentChanged(granted: Boolean) {
        if (granted) start() else stop()
    }

    val coroutineHandler = CoroutineExceptionHandler { _, throwable ->
        report(throwable, source = "coroutine")
    }

    /** Logs [throwable] locally and, with consent, sends it to Sentry. Never throws. */
    fun report(throwable: Throwable, source: String) {
        runCatching {
            // Appended (several crashes per run are possible now that most are survivable), but
            // restarted once it grows past this so a crash loop can't fill the disk.
            if (crashLogFile.length() > MaxCrashLogBytes) crashLogFile.delete()
            crashLogFile.appendText("--- ${java.time.LocalDateTime.now()} [$source]\n${throwable.stackTraceToString()}\n")
        }
        if (!started) return
        runCatching {
            Sentry.captureException(throwable) { scope -> scope.setTag("source", source.substringBefore(':')) }
        }
    }

    /** Blocks up to [timeoutMillis] so a report captured right before the process exits still goes out. */
    fun flush(timeoutMillis: Long = 2_000) {
        if (started) runCatching { Sentry.flush(timeoutMillis) }
    }

    private fun start() {
        if (started || dsn.isBlank()) return
        runCatching {
            Sentry.init { options ->
                options.dsn = dsn
                options.release = "sbs3dViewer@${System.getProperty("app.version").orEmpty()}"
                // Set by the jpackage launcher only, so `./gradlew run` crashes stay out of the
                // production view in Sentry.
                options.environment = if (System.getProperty("jpackage.app-path") != null) "production" else "development"
                // Replaces the deprecated sendDefaultPii = false, so the IP address/user is never
                // attached. PRIVACY.md relies on this. Once any dataCollection field is set, every
                // unset one defaults to *on*, so each one that could carry PII is pinned off here.
                options.dataCollection.userInfo = false
                options.dataCollection.filePaths = false
                options.dataCollection.cookies = KeyValueCollectionBehavior.off()
                options.dataCollection.httpBodies = emptySet()
                // Default would attach the machine's hostname, which is often the user's name.
                options.isAttachServerName = false
                // install() above already owns the default handler and reports through report().
                options.isEnableUncaughtExceptionHandler = false
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrub(event) }
            }
            started = true
        }
    }

    private fun stop() {
        if (!started) return
        started = false
        runCatching { Sentry.close() }
    }

    private fun scrub(event: SentryEvent): SentryEvent {
        event.exceptions?.forEach { it.value = it.value?.let(::scrub) }
        event.message?.let { message ->
            message.formatted = message.formatted?.let(::scrub)
            message.message = message.message?.let(::scrub)
        }
        // None are added today, but anything added later must not bypass the scrubbing.
        event.breadcrumbs?.forEach { breadcrumb -> breadcrumb.message = breadcrumb.message?.let(::scrub) }
        return event
    }

    /**
     * Strips the user's home dir, absolute paths, media file names and the Windows account name.
     * Also used by the startup crash report (FeedbackScreen), so a trace mailed through the contact
     * form leaks no more than what Sentry gets.
     */
    fun scrub(text: String): String {
        val withoutHome = if (userHome.isNotBlank()) text.replace(userHome, "<home>", ignoreCase = true) else text
        val withoutPaths = fileNameRegex.replace(pathRegex.replace(withoutHome, "<path>"), "<file>")
        // Last, and only as a whole word: a short Windows account name ("al") would otherwise
        // also mangle unrelated words.
        return if (userName.isNotBlank()) {
            withoutPaths.replace(Regex("""\b${Regex.escape(userName)}\b""", RegexOption.IGNORE_CASE), "<user>")
        } else withoutPaths
    }
}
