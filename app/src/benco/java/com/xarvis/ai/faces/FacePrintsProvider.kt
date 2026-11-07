package com.xarvis.ai.faces

import android.content.Context

/** The benco build has no face recognition (no camera, no TensorFlow Lite): it's on the S22. */
object FacePrintsProvider {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): FacePrints? = null
}
