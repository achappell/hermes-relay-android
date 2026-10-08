package com.achappell.hermesrelay

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The stores and controllers that belong to the process, not to an Activity.
 *
 * Built once and shared by every Activity instance, so a recreated screen sees
 * the same configuration the running Home runtime was built against.
 */
internal class HermesRelayServices(context: Context) {
    val credentials = KeystoreRelayCredentialStore(context)
    val historyStore = FileAndroidHistoryStore(context)
    val pairings = FileHomeClientPairingStore(context)
    val clientService = HttpHomeClientService()
    val configuration = RelayConfigurationController(
        profiles = FileRelayProfileStore(context),
        credentials = credentials,
        history = historyStore,
        homeClientPairings = pairings,
        standardChecker = OkHttpStandardConnectionChecker(),
    )
    val homePairing = HomeClientPairingCoordinator(
        store = pairings,
        credentials = credentials,
        service = clientService,
        configuration = configuration,
        deviceLabel = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android",
    )
}

/** Owns the process-scoped Home runtime (`ANDROID-HOME-07`). */
class HermesRelayApplication : Application() {
    internal val services: HermesRelayServices by lazy { HermesRelayServices(this) }

    /**
     * The content-free connection journal, kept in every build. Debug builds also
     * echo each line to logcat under one tag.
     */
    internal val journal: DiagnosticsJournal by lazy {
        val file = FileDiagnosticsJournal.forApplication(this)
        if (LogcatEchoJournal.isDebuggable(this)) LogcatEchoJournal(file) else file
    }

    /** Replaceable so an instrumented test can supply a runtime built on a fake Home. */
    internal var homeRuntimeBox: HomeRuntimeBox
        get() = boxOverride ?: processBox
        set(value) {
            boxOverride = value
        }

    private var boxOverride: HomeRuntimeBox? = null
    private val processBox: HomeRuntimeBox by lazy { HomeRuntimeBox(journal, ::createHomeRuntime) }

    override fun onCreate() {
        super.onCreate()
        // A leftover export copy from an earlier share is not needed any more.
        DiagnosticsShare.clearExport(this)
    }

    private fun createHomeRuntime(onTornDown: () -> Unit): HomeRuntime {
        // One router serves every mode: a Standard Profile talks to Hermes
        // directly, every other Profile keeps the Home bridge. Each transport
        // reports NotConfigured until a Profile with its own stored credential
        // exists, so the shell stays honest about an unconfigured relay without
        // needing a separate bootstrap adapter. The two never share a socket,
        // a credential or a turn, and neither is a fallback for the other.
        // The API-level policy is read once, here at the runtime root, and injected.
        val platform = AndroidPlatform.current(this)
        val homeClient = OkHttpRelaySessionClient(
            collection = { services.configuration.collection },
            credentials = services.credentials,
            audioSink = AudioTrackAudioSink(driverFactory = platformAudioTrackDriverFactory(platform)),
            clientClaims = HomeClientClaimProvider(
                services.pairings,
                services.credentials,
                services.clientService,
            ),
            journal = journal,
        )
        val standardClient = OkHttpStandardSessionClient(
            collection = { services.configuration.collection },
            credentials = services.credentials,
            journal = journal,
        )
        val selectedMode = { services.configuration.collection.selected?.mode }
        val clientPort = ProfileModeClientPort(selectedMode, homeClient, standardClient)
        val mainHandler = Handler(Looper.getMainLooper())
        val runtime = HomeRuntime(
            clientPort = clientPort,
            speechInput = PlatformSpeechInput(this, platform),
            historyStore = services.historyStore,
            postToMain = { mainHandler.post(it) },
            workExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "hermes-android-work").apply { isDaemon = true }
            },
            onTornDown = {
                services.configuration.switchGuard = { false }
                onTornDown()
            },
            journal = journal,
            selectedMode = selectedMode,
            newConversationLabel = getString(R.string.android_new_conversation),
        )
        // Profile and mode switches consult the runtime: a Standard turn that is
        // active, uncertain or still finishing blocks them.
        services.configuration.switchGuard = { runtime.isConversationBusy }
        return runtime
    }
}
