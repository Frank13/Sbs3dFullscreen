import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.prefs.Preferences

/**
 * Whether the user has answered the one-time analytics consent prompt (AnalyticsConsentDialog),
 * and what they answered - same Preferences API as FileChoosers.kt's LastDirectoryPreference.
 */
private object AnalyticsConsentPreference {
    private const val KeyAnswered = "analyticsConsentAnswered"
    private const val KeyGranted = "analyticsConsentGranted"
    private val prefs = Preferences.userNodeForPackage(AppViewModel::class.java)

    fun hasAnswered(): Boolean = prefs.getBoolean(KeyAnswered, false)
    fun isGranted(): Boolean = prefs.getBoolean(KeyGranted, false)

    fun save(granted: Boolean) {
        prefs.putBoolean(KeyAnswered, true)
        prefs.putBoolean(KeyGranted, granted)
    }
}

/** Random per-install id GA4 requires as client_id; generated once and persisted like the pref above. */
private object ClientIdPreference {
    private const val Key = "analyticsClientId"
    private val prefs = Preferences.userNodeForPackage(AppViewModel::class.java)

    fun loadOrCreate(): String {
        prefs.get(Key, null)?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.put(Key, created)
        return created
    }
}

/**
 * Sends usage events to Google Analytics 4 via its Measurement Protocol (plain HTTPS POST) -
 * there is no native GA SDK for desktop JVM apps. Nothing is ever sent unless the user granted
 * consent through AnalyticsConsentDialog (shown once, from Main.kt).
 */
object Analytics {
    // Read from local.properties (git-ignored) via build.gradle.kts' jvmArgs, same mechanism as
    // app.version - keeps the api secret out of source/git history. Blank when local.properties
    // hasn't been filled in (e.g. a fresh clone), in which case logEvent below just no-ops.
    private val measurementId = System.getProperty("ga.measurementId").orEmpty()
    private val apiSecret = System.getProperty("ga.apiSecret").orEmpty()
    private val isConfigured = measurementId.isNotBlank() && apiSecret.isNotBlank()

    // Plain HttpURLConnection rather than java.net.http.HttpClient: HttpClient opens an NIO
    // Selector, whose Windows wakeup pipe is an AF_UNIX socket that fails to connect on some
    // machines ("Unable to establish loopback connection", seen on Store installs) - and since
    // that happened in this object's initializer, it crashed the app at startup.
    private const val TimeoutMillis = 5_000
    private val mapper = jacksonObjectMapper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One id per app run, not persisted - lets GA4 group the events of a single launch into one
    // session even though Measurement Protocol hits (unlike gtag.js) don't get one automatically.
    private val sessionId = System.currentTimeMillis().toString()

    private val clientId: String by lazy { ClientIdPreference.loadOrCreate() }

    var consentGranted: Boolean = AnalyticsConsentPreference.isGranted()
        private set

    val hasAnsweredConsent: Boolean get() = AnalyticsConsentPreference.hasAnswered()

    fun setConsent(granted: Boolean) {
        consentGranted = granted
        AnalyticsConsentPreference.save(granted)
    }

    fun logEvent(name: String, params: Map<String, Any?> = emptyMap()) {
        if (!consentGranted || !isConfigured) return
        val eventParams = params + mapOf("session_id" to sessionId, "engagement_time_msec" to "100")
        val body = mapOf(
            "client_id" to clientId,
            "events" to listOf(mapOf("name" to name, "params" to eventParams)),
        )
        val json = mapper.writeValueAsString(body)
        scope.launch {
            try {
                val url = URI.create("https://www.google-analytics.com/mp/collect?measurement_id=$measurementId&api_secret=$apiSecret").toURL()
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = TimeoutMillis
                    connection.readTimeout = TimeoutMillis
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Best-effort telemetry: a failed send must never surface to the user or crash the app.
            }
        }
    }
}
