package com.xarvis.ai.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed interface ModelDownload {
    data object Idle : ModelDownload
    data class Running(val done: Long, val total: Long) : ModelDownload
    data class Waiting(val why: String) : ModelDownload
    data class Failed(val why: String) : ModelDownload
}

/**
 * Downloads Gemma 4 E2B from Hugging Face into XARVIS's model folder with Android's download
 * manager, which keeps going when XARVIS is closed and resumes after Wi-Fi drops. The file is
 * written under a temporary name and renamed when complete, so a half-finished download is
 * never loaded as a model. [onComplete] runs when the model is ready to load.
 */
class ModelDownloader(context: Context, private val onComplete: suspend () -> Unit) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)
    private val prefs = appContext.getSharedPreferences("model_download", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null

    private val _state = MutableStateFlow<ModelDownload>(ModelDownload.Idle)
    val state: StateFlow<ModelDownload> = _state.asStateFlow()

    private val modelDir get() = File(appContext.getExternalFilesDir(null), "models")
    private val partFile get() = File(modelDir, "$MODEL_FILE.download")
    private val finalFile get() = File(modelDir, MODEL_FILE)

    /** Follows a download started before XARVIS was last closed, which carried on meanwhile. */
    fun resume() {
        if (prefs.getLong(KEY_ID, -1L) != -1L) watch()
    }

    fun start() {
        if (_state.value is ModelDownload.Running || prefs.getLong(KEY_ID, -1L) != -1L) return
        modelDir.mkdirs()
        partFile.delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("XARVIS AI model")
            .setDescription("Gemma 4 (about 2.4 GB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(appContext, null, "models/${partFile.name}")
            .setAllowedOverMetered(true) // Rex chose mobile data (faster for him than his Wi-Fi)
            .setAllowedOverRoaming(false)
        val id = try {
            manager.enqueue(request)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the model download", e)
            _state.value = ModelDownload.Failed(e.message ?: "couldn't start")
            return
        }
        prefs.edit().putLong(KEY_ID, id).apply()
        watch()
    }

    private fun watch() {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive) {
                val id = prefs.getLong(KEY_ID, -1L)
                if (id == -1L) return@launch
                val cursor = manager.query(DownloadManager.Query().setFilterById(id))
                if (cursor == null || !cursor.moveToFirst()) {
                    cursor?.close()
                    finish(ModelDownload.Failed("the download was cancelled"))
                    return@launch
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                cursor.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (partFile.renameTo(finalFile)) {
                            finish(ModelDownload.Idle)
                            onComplete()
                        } else {
                            finish(ModelDownload.Failed("couldn't save the model file"))
                        }
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        finish(ModelDownload.Failed(failure(reason)))
                        return@launch
                    }
                    DownloadManager.STATUS_PAUSED -> _state.value = ModelDownload.Waiting(pause(reason))
                    else -> _state.value = ModelDownload.Running(done, total)
                }
                delay(POLL_MS)
            }
        }
    }

    private fun finish(result: ModelDownload) {
        prefs.edit().remove(KEY_ID).apply()
        if (result is ModelDownload.Failed) partFile.delete()
        _state.value = result
    }

    private fun pause(reason: Int) = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for internet"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "connection dropped, retrying soon"
        else -> "paused"
    }

    private fun failure(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough free space (it needs about 2.5 GB)"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "the connection broke"
        else -> "error $reason"
    }

    companion object {
        private const val TAG = "XarvisModelDownload"
        private const val KEY_ID = "downloadId"
        private const val POLL_MS = 1_000L
        /** The general build of Gemma 4 E2B, which runs on the CPU (the -gpu build can't). */
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILE"
    }
}
