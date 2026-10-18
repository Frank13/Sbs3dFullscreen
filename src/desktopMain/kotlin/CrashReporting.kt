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
                // sendDefaultPii is left at its default (false): no IP address/user attached.
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
        return event
    }

    private fun scrub(text: String): String {
        val withoutHome = if (userHome.isNotBlank()) text.replace(userHome, "<home>", ignoreCase = true) else text
        return pathRegex.replace(withoutHome, "<path>")
    }
}
