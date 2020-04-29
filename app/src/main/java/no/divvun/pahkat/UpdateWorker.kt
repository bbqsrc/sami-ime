package no.divvun.pahkat


import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.work.*
import arrow.core.Either
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.divvun.SpellerPackage
import no.divvun.pahkat.client.PackageKey
import no.divvun.pahkat.client.PrefixPackageStore
import no.divvun.pahkat.client.TransactionAction
import no.divvun.pahkat.client.delegate.PackageDownloadDelegate
import no.divvun.pahkat.client.delegate.PackageTransactionDelegate
import no.divvun.pahkat.client.ffi.orThrow
import no.divvun.pahkat.client.fromJson
import no.divvun.prefixPath
import no.divvun.spellers
import no.divvun.workData
import timber.log.Timber
import java.util.concurrent.TimeUnit

const val WORKMANAGER_TAG_UPDATE = "no.divvun.pahkat.client.UPDATE"
const val WORKMANAGER_NAME_UPDATE = "no.divvun.pahkat.client.UPDATE_NAME"

const val KEY_PACKAGE_KEY = "no.divvun.pahkat.client.packageKey"
const val KEY_PACKAGE_STORE_PATH = "no.divvun.pahkat.client.packageStorePath"
const val KEY_OBJECT = "no.divvun.pahkat.client.object"
const val KEY_OBJECT_TYPE = "no.divvun.pahkat.client.objectType"


class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val packageStorePathValue get() = inputData.getString(KEY_PACKAGE_STORE_PATH)

    private inner class DownloadDelegate :
            PackageDownloadDelegate {
        val coroutineScope =
                CoroutineScope(Dispatchers.IO)
        override var isDownloadCancelled = false

        override fun onDownloadCancel(packageKey: PackageKey) {
            Timber.d("cancel: $packageKey")
            coroutineScope.launch {
                setProgressAsync(
                        UpdateProgress.Download.Cancelled(
                                packageKey.toString()
                        ).toData()
                ).await()
            }
        }

        override fun onDownloadComplete(packageKey: PackageKey, path: String) {
            Timber.d("complete: $packageKey $path")
            coroutineScope.launch {
                setProgressAsync(
                        UpdateProgress.Download.Completed(
                                packageKey.toString(),
                                path
                        ).toData()
                ).await()
            }
        }

        override fun onDownloadError(packageKey: PackageKey, error: Exception) {
            Timber.e("error: $packageKey $error")

            coroutineScope.launch {
                Timber.d("OH LAWD HE COMIN'")
                val data = UpdateProgress.Download.Error(
                        packageKey.toString(),
                        error
                ).toData()
                Timber.d("$data")
                setProgressAsync(data).await()
                Timber.d("Finished waiting.")
            }
        }

        override fun onDownloadProgress(packageKey: PackageKey, current: Long, maximum: Long) {
            Timber.d(
                    "progress: $packageKey $current/$maximum"
            )
            coroutineScope.launch {
                setProgressAsync(
                        UpdateProgress.Download.Downloading(
                                packageKey.toString(),
                                current,
                                maximum
                        ).toData()
                ).await()
            }
        }
    }


    private inner class TransactionDelegate :
            PackageTransactionDelegate {
        val coroutineScope =
                CoroutineScope(Dispatchers.IO)

        internal var isCancelled = false

        override fun isTransactionCancelled(id: Long): Boolean = isCancelled

        override fun onTransactionCancelled(id: Long) {
            coroutineScope.launch {
                setProgressAsync(UpdateProgress.TransactionProgress.Cancelled.toData()).await()
            }
        }

        override fun onTransactionCompleted(id: Long) {
            coroutineScope.launch {
                setProgressAsync(UpdateProgress.TransactionProgress.Completed.toData()).await()
            }
        }

        override fun onTransactionError(
                id: Long,
                packageKey: PackageKey?,
                error: java.lang.Exception?
        ) {
            coroutineScope.launch {
                setProgressAsync(
                        UpdateProgress.TransactionProgress.Error(
                                packageKey,
                                error
                        ).toData()
                ).await()
            }
        }

        override fun onTransactionInstall(id: Long, packageKey: PackageKey?) {
            coroutineScope.launch {
                setProgressAsync(
                        UpdateProgress.TransactionProgress.Install(
                                packageKey!!
                        ).toData()
                ).await()
            }
        }

        override fun onTransactionUninstall(id: Long, packageKey: PackageKey?) {
            coroutineScope.launch {
                setProgressAsync(
                        UpdateProgress.TransactionProgress.Uninstall(
                                packageKey!!
                        ).toData()
                ).await()
            }
        }

        override fun onTransactionUnknownEvent(id: Long, event: Long) {
            coroutineScope.launch {
                setProgressAsync(
                        UpdateProgress.TransactionProgress.UnknownEvent(
                                event
                        ).toData()
                ).await()
            }
        }

    }

    private val downloadDelegate = DownloadDelegate()
    private val transactionDelegate = TransactionDelegate()

    override fun onStopped() {
        downloadDelegate.isDownloadCancelled = true
        transactionDelegate.isCancelled = true
        super.onStopped()
    }

    override fun doWork(): Result {
        Timber.d("Starting work")
        val packageStorePath = packageStorePathValue ?: return Result.failure(
                IllegalArgumentException("packageStorePath cannot be null").toData()
        )

        val packageStore =
                when (val result = PrefixPackageStore.open(packageStorePath)) {
                    is Either.Left -> return Result.failure(result.a.toData())
                    is Either.Right -> result.b
                }

        Timber.d("Refreshing repos")
        packageStore.forceRefreshRepos().orThrow()

        val enabledSubtypes = applicationContext.activeInputMethodSubtype()
        val activePackages = resolveActivePackageKeys(enabledSubtypes, spellers)
        Timber.d("Active packages existing $activePackages")
        if (enabledSubtypes.size != activePackages.size) {
            Timber.d("Some subtypes doesn't have spellers")
            Timber.d("Enabled Subtypes: $enabledSubtypes")
            Timber.d("Found Packages: $activePackages")
        }

        for (packageKey in activePackages) {
            setProgressAsync(
                    UpdateProgress.Download.Starting(
                            packageKey.toString()
                    ).toData()
            )
            Timber.d("Starting download")

            // This blocks to completion.
            when (val r = packageStore.download(packageKey, downloadDelegate)) {
                is Either.Left -> return Result.retry()
            }

            Timber.d("Download complete $packageKey")
            if (downloadDelegate.coroutineScope.isActive) {
                Timber.d("Looping 250ms delay")
                Thread.sleep(250)
            }
            // Normally download is completed here

            // Our action is installing
            Timber.d("Starting install $packageKey")
            val actions = listOf(TransactionAction.install(packageKey, Unit))

            val tx =
                    when (val result = packageStore.transaction(actions)) {
                        is Either.Left -> return Result.failure(result.a.toData())
                        is Either.Right -> result.b
                    }

            // This blocks to completion.
            tx.process(transactionDelegate)

            Timber.d("Install complete $packageKey")

            if (transactionDelegate.coroutineScope.isActive) {
                Timber.d("Looping 250ms delay")
                Thread.sleep(250)
            }

        }
        applicationContext.storeSubtypes(enabledSubtypes)
        Timber.d("Work success")
        return Result.success()
    }

    private fun resolveActivePackageKeys(enabledSubtypes: Set<String>, activePackages: Map<String, SpellerPackage>): Set<PackageKey> {
        return activePackages.filterKeys { it in enabledSubtypes }.values.map { it.packageKey }.toSet()
    }

    companion object {
        fun restartUpdateWorkerIfNotRunning(context: Context) {
            val workManager = context.workManager()

            if (workManager.getWorkInfosByTag(WORKMANAGER_TAG_UPDATE).get().all { it.state != WorkInfo.State.RUNNING }) {
                Timber.d("Work not currently running restarting worker")
                workManager.cancelUniqueWork(WORKMANAGER_NAME_UPDATE)
                ensurePeriodicPackageUpdates(context, prefixPath(context))
            } else {
                Timber.d("Update already running, skipping restart")
            }
        }

        fun ensurePeriodicPackageUpdates(
                context: Context,
                prefixPath: String
        ): String {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                    .addTag(WORKMANAGER_TAG_UPDATE)
                    .setInputData(prefixPath.workData())
                    .setConstraints(
                            Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.UNMETERED)
                                    .setRequiresStorageNotLow(true)
                                    .setRequiresBatteryNotLow(true)
                                    .build()
                    )
                    .build()

            context.workManager().enqueueUniquePeriodicWork(WORKMANAGER_NAME_UPDATE, ExistingPeriodicWorkPolicy.KEEP, req)
            return prefixPath
        }
    }
}


private const val SHARED_PREF_NAME = "SUBTYPES_SHARED_PREFERENCES"
private const val PREF_KEY_ENABLED_SUBTYPES = "ENABLED_SUBTYPES"

fun Context.hasSubtypesChanged(): Boolean {
    val gson = Gson()
    val prefs = getSharedPreferences(SHARED_PREF_NAME, MODE_PRIVATE)
    val jsonSubtypes = prefs.getString(PREF_KEY_ENABLED_SUBTYPES, "[]")!!
    val list = gson.fromJson<List<String>>(jsonSubtypes).toSet()
    Timber.d("hasSubtypesChanged() Active: ${activeInputMethodSubtype()}, stored: $list")
    return activeInputMethodSubtype().toSet() != list
}

fun Context.storeSubtypes(subtypes: Set<String>) {
    val gson = Gson()
    val prefs = getSharedPreferences(SHARED_PREF_NAME, MODE_PRIVATE)
    val json = gson.toJson(subtypes.toList())
    prefs.edit().putString(PREF_KEY_ENABLED_SUBTYPES, json).apply()
}

fun Context.restartUpdaterIfSubtypesChanged() {
    if (hasSubtypesChanged()) {
        UpdateWorker.restartUpdateWorkerIfNotRunning(this)
    }

}

@SuppressLint("NewApi")
fun Context.activeInputMethodSubtype(): Set<String> {
    val imm = getSystemService<InputMethodManager>()!!
    val inputMethods = imm.inputMethodList.filter { it.id.contains(packageName) }
    Timber.d("Relevant InputMethods: ${inputMethods.map { it.packageName }}")
    return inputMethods.flatMap { imi ->
        imm.getEnabledInputMethodSubtypeList(imi, true).map { ims ->
            val language = ims.locale.takeWhile { it != '_' }
            Timber.d("Language $language")
            language
        }
    }.toSet()
}

