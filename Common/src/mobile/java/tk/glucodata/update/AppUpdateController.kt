package tk.glucodata.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.BuildConfig
import java.io.File

/** Where the download/install sequence currently stands. */
enum class UpdateStage {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    INSTALLING
}

data class AppUpdateUiState(
    /** False on debug builds and store installs; the UI then explains instead of offering. */
    val supported: Boolean = true,
    val blocker: UpdateEligibility.Blocker? = null,
    /** The one-time opt-in card has not been answered yet. */
    val introPending: Boolean = false,
    val autoCheckEnabled: Boolean = false,
    /** The https URL releases are read from. */
    val updateSource: String = "",
    val isDefaultUpdateSource: Boolean = true,
    val checking: Boolean = false,
    val lastCheckAtMillis: Long = 0L,
    val error: UpdateError? = null,
    val available: AvailableUpdate? = null,
    val stage: UpdateStage = UpdateStage.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val readyFilePath: String? = null,
    val bannerDismissed: Boolean = true
) {
    val installedVersionName: String get() = BuildConfig.BASE_VERSION_NAME
    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE

    /** The dashboard shows at most one of these, and only when there is something to say. */
    val showIntroCard: Boolean get() = supported && introPending
    val showUpdateCard: Boolean
        get() = supported && !introPending && available != null && !bannerDismissed

    val downloadFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Single owner of updater state, shared by the dashboard card and the App updates screen.
 *
 * An object rather than a ViewModel because the same state has to be reachable from a
 * [android.content.BroadcastReceiver] (install result) and from a WorkManager worker
 * (background check), neither of which has a ViewModelStore.
 */
object AppUpdateController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    /**
     * Whether the download now running was started by a button that promised to install too.
     * The user has already made that decision once; stopping at a second "Install" press would
     * be asking for the same confirmation twice. The system's own prompt still stands between
     * this and anything being replaced.
     */
    private var installWhenReady = false

    /**
     * Guards [initialize] to actually running once per process, matching its own doc comment.
     * Cards and screens each call it from their own `LaunchedEffect(Unit)` — several can be
     * mounted at once (e.g. the dashboard banner and an update card) — so without this, the
     * staged-file recovery scan below would run concurrently per mount and race the state it
     * writes against whatever a real download/install already in progress has moved on to.
     * The set-to-true is safe unsynchronized: every call site invokes this synchronously from
     * the main thread before the coroutine is launched. The reset-to-false on failure is not —
     * it runs on the IO dispatcher inside the launched coroutine's catch block, so it needs
     * @Volatile for that write to be visible to the next call's main-thread read.
     */
    @Volatile
    private var initialized = false

    /**
     * Called once per app start. Reloads persisted state, drops a staged APK left over from a
     * completed install, recovers one that survived a process death after verifying, and
     * (re)schedules the daily check when it is enabled.
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                initializeBody(appContext)
            } catch (t: Throwable) {
                // A failure partway through (verify()/blocker() throwing, etc.) must not
                // permanently latch `initialized` — the next mount has to get a real retry,
                // same as every call site got before this guard existed.
                initialized = false
                throw t
            }
        }
    }

    private suspend fun initializeBody(appContext: Context) {
        val blocker = UpdateEligibility.blocker(appContext)
        if (blocker != null) {
            AppUpdateCheckWorker.cancel(appContext)
            UpdateDownloader.clearStaged(appContext)
            _state.update { it.copy(supported = false, blocker = blocker, introPending = false) }
            return
        }

        // A cached release that is no longer newer than the running build means the update
        // was installed (or a downgrade happened); either way the staged file is dead weight.
        val cached = AppUpdateSettings.cachedUpdate(appContext)
        val recoveredReadyFilePath = when {
            cached == null -> null
            !isStillNewer(cached) -> {
                AppUpdateSettings.clearCachedUpdate(appContext)
                UpdateDownloader.clearStaged(appContext)
                null
            }
            // readyFilePath only ever lived in memory, so a process death after a completed,
            // verified download otherwise strands the file: unreachable to the UI, but never
            // cleaned up either since nothing here else deletes it. Recover it when the
            // staged file still matches and verifies against the cached release; a file that
            // doesn't (a partial download interrupted mid-transfer, or a stale one from a
            // release that has since changed) is dead weight rather than resumable, so it
            // gets the same cleanup a failed download would.
            else -> {
                val staged = UpdateDownloader.stagedFile(appContext, cached.artifact)
                if (staged.isFile && ApkVerifier.verify(appContext, staged, cached) == null) {
                    staged.absolutePath
                } else {
                    UpdateDownloader.clearStaged(appContext)
                    null
                }
            }
        }

        refreshFromSettings(appContext)
        if (recoveredReadyFilePath != null) {
            _state.update { current ->
                // Both conditions matter: `stage == IDLE` means nothing else (a real download,
                // an install) has moved on while verify() above was running; `available`
                // matching `cached`'s identity means a "check now"/daily-worker race landing
                // between capturing `cached` and this update didn't leave `readyFilePath`
                // pointing at a different release than the one the UI now describes.
                if (current.stage != UpdateStage.IDLE || current.available?.identity != cached?.identity) {
                    return@update current
                }
                current.copy(
                    stage = UpdateStage.READY_TO_INSTALL,
                    readyFilePath = recoveredReadyFilePath,
                    // A stale persisted check error (e.g. a network blip on an unrelated
                    // daily check) must not mask the ready-to-install card behind it.
                    error = null
                )
            }
            // The guard above can decide not to apply a file that just passed verification —
            // at that point it isn't reachable through state and never will be (this recovery
            // scan only runs once per process), so it would otherwise sit on disk forever.
            if (_state.value.readyFilePath != recoveredReadyFilePath) {
                UpdateDownloader.clearStaged(appContext)
            }
        }
        if (AppUpdateSettings.isAutoCheckEnabled(appContext)) {
            AppUpdateCheckWorker.schedule(appContext)
        }
    }

    /** Re-reads persisted state. Safe to call from a worker thread. */
    fun refreshFromSettings(context: Context) {
        val appContext = context.applicationContext
        val blocker = UpdateEligibility.blocker(appContext)
        val cached = AppUpdateSettings.cachedUpdate(appContext)?.takeIf { isStillNewer(it) }
        val dismissed = AppUpdateSettings.dismissedIdentity(appContext)
        _state.update { current ->
            current.copy(
                supported = blocker == null,
                blocker = blocker,
                introPending = blocker == null && !AppUpdateSettings.isIntroAnswered(appContext),
                autoCheckEnabled = AppUpdateSettings.isAutoCheckEnabled(appContext),
                updateSource = AppUpdateSettings.updateSource(appContext),
                isDefaultUpdateSource = AppUpdateSettings.isDefaultUpdateSource(appContext),
                lastCheckAtMillis = AppUpdateSettings.lastCheckAtMillis(appContext),
                // A live error (download failed, signature mismatch) outranks the persisted
                // check error — otherwise re-entering a screen quietly wipes what the user is
                // reading. Only fall back to the stored one when nothing is in flight.
                error = current.error
                    ?: AppUpdateSettings.lastError(appContext).takeIf { current.stage == UpdateStage.IDLE },
                available = cached,
                bannerDismissed = cached == null || dismissed == cached.identity,
                // A staged download belonging to a superseded release must not stay "ready".
                stage = if (cached == null && current.stage == UpdateStage.READY_TO_INSTALL) {
                    UpdateStage.IDLE
                } else {
                    current.stage
                }
            )
        }
    }

    /** Answers the one-time opt-in card. */
    fun answerIntro(context: Context, enable: Boolean) {
        val appContext = context.applicationContext
        AppUpdateSettings.setAutoCheckEnabled(appContext, enable)
        refreshFromSettings(appContext)
        if (enable) {
            AppUpdateCheckWorker.schedule(appContext)
            checkNow(appContext)
        } else {
            AppUpdateCheckWorker.cancel(appContext)
        }
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) = answerIntro(context, enabled)

    /** Points the updater at a different https source. Pass null to restore the default. */
    fun setUpdateSource(context: Context, url: String?) {
        val appContext = context.applicationContext
        AppUpdateSettings.setUpdateSource(appContext, url)
        // Whatever the previous source found no longer applies.
        AppUpdateSettings.clearCachedUpdate(appContext)
        AppUpdateSettings.setDismissedIdentity(appContext, null)
        UpdateDownloader.clearStaged(appContext)
        _state.update { it.copy(stage = UpdateStage.IDLE, readyFilePath = null) }
        refreshFromSettings(appContext)
        if (AppUpdateSettings.isAutoCheckEnabled(appContext)) checkNow(appContext)
    }

    fun checkNow(context: Context) {
        val appContext = context.applicationContext
        if (_state.value.checking || !UpdateEligibility.isSupported(appContext)) return
        _state.update { it.copy(checking = true, error = null) }
        scope.launch {
            val result = UpdateSourceClient.check(AppUpdateSettings.updateSource(appContext))
            AppUpdateSettings.recordCheck(appContext, result, System.currentTimeMillis())
            // The dismissal is keyed by release identity, so finding the same release again
            // keeps the card hidden while a genuinely new one still gets to show up once.
            // Clearing it here would turn every daily check into a re-nag.
            _state.update { it.copy(checking = false, error = null) }
            refreshFromSettings(appContext)
            (result as? UpdateCheckResult.Failed)?.let { failure ->
                _state.update { it.copy(error = failure.error) }
            }
        }
    }

    fun startDownload(context: Context, autoInstall: Boolean = false) {
        val appContext = context.applicationContext
        val update = _state.value.available ?: return
        if (_state.value.stage == UpdateStage.DOWNLOADING) return
        installWhenReady = autoInstall

        _state.update {
            it.copy(
                stage = UpdateStage.DOWNLOADING,
                downloadedBytes = 0L,
                totalBytes = update.artifact.sizeBytes,
                error = null,
                readyFilePath = null
            )
        }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            when (val outcome = UpdateDownloader.download(appContext, update) { read, total ->
                _state.update { it.copy(downloadedBytes = read, totalBytes = total) }
            }) {
                is UpdateDownloader.Outcome.Failed -> failDownload(appContext, outcome.error)

                is UpdateDownloader.Outcome.Ok -> {
                    _state.update { it.copy(stage = UpdateStage.VERIFYING) }
                    val problem = withContext(Dispatchers.IO) {
                        ApkVerifier.verify(appContext, outcome.file, update)
                    }
                    if (problem != null) {
                        outcome.file.delete()
                        failDownload(appContext, problem)
                    } else {
                        _state.update {
                            it.copy(
                                stage = UpdateStage.READY_TO_INSTALL,
                                readyFilePath = outcome.file.absolutePath,
                                error = null
                            )
                        }
                        // Hand straight over to the system installer. If the permission is
                        // missing, install() reports it and the UI offers the settings trip;
                        // the staged file stays put either way.
                        if (installWhenReady) install(appContext)
                    }
                }
            }
        }
    }

    fun cancelDownload(context: Context) {
        downloadJob?.cancel()
        downloadJob = null
        installWhenReady = false
        UpdateDownloader.clearStaged(context.applicationContext)
        _state.update {
            it.copy(stage = UpdateStage.IDLE, downloadedBytes = 0L, readyFilePath = null, error = null)
        }
    }

    /**
     * Commits the staged APK. Control passes to the system installer, which shows its own
     * confirmation; results come back through [AppUpdateInstallReceiver].
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val path = _state.value.readyFilePath ?: return
        val file = File(path)
        if (!file.isFile) {
            _state.update { it.copy(stage = UpdateStage.IDLE, readyFilePath = null, error = UpdateError.STORAGE) }
            return
        }
        _state.update { it.copy(stage = UpdateStage.INSTALLING, error = null) }
        scope.launch {
            val problem = AppUpdateInstaller.install(appContext, file)
            if (problem != null) {
                _state.update { it.copy(stage = UpdateStage.READY_TO_INSTALL, error = problem) }
            }
        }
    }

    /** Called from the install status receiver. */
    fun onInstallFailed(context: Context, error: UpdateError) {
        // Keep the staged APK when it survived, so "Install" can simply be tapped again.
        val ready = _state.value.readyFilePath?.let { File(it).isFile } == true
        _state.update {
            it.copy(
                stage = if (ready) UpdateStage.READY_TO_INSTALL else UpdateStage.IDLE,
                error = error
            )
        }
        if (!ready) UpdateDownloader.clearStaged(context.applicationContext)
    }

    /** Hides the dashboard card for this release only. */
    fun dismissBanner(context: Context) {
        val appContext = context.applicationContext
        val identity = _state.value.available?.identity ?: return
        AppUpdateSettings.setDismissedIdentity(appContext, identity)
        _state.update { it.copy(bannerDismissed = true) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun failDownload(context: Context, error: UpdateError) {
        installWhenReady = false
        UpdateDownloader.clearStaged(context)
        _state.update {
            it.copy(stage = UpdateStage.IDLE, readyFilePath = null, downloadedBytes = 0L, error = error)
        }
    }

    private fun isStillNewer(update: AvailableUpdate): Boolean =
        GithubReleaseParser.isNewerThanInstalled(
            update = update,
            installedVersionCode = BuildConfig.VERSION_CODE,
            installedVersionName = BuildConfig.BASE_VERSION_NAME
        )
}
