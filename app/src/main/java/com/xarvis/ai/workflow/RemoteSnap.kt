package com.xarvis.ai.workflow

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred

/**
 * A linked phone with no camera (the benco) asked this phone for a photo: the link waits here while
 * XARVIS Eyes counts down and shoots, then sends the shot back. Only one request at a time; a newer
 * one replaces an older one.
 */
object RemoteSnap {
    /** [uri]: the copy saved in this phone's Gallery (null if that failed); [jpeg]: the photo itself. */
    class Shot(val uri: Uri?, val jpeg: ByteArray)

    private var waiting: CompletableDeferred<Shot?>? = null

    @Synchronized
    fun begin(): CompletableDeferred<Shot?> {
        waiting?.complete(null)
        return CompletableDeferred<Shot?>().also { waiting = it }
    }

    /** Eyes took the photo, or ([shot] null) closed without taking it. */
    @Synchronized
    fun deliver(shot: Shot?) {
        waiting?.complete(shot)
        waiting = null
    }

    /** The link gave up waiting for [request]. */
    @Synchronized
    fun cancel(request: CompletableDeferred<Shot?>) {
        if (waiting === request) waiting = null
        request.complete(null)
    }
}
