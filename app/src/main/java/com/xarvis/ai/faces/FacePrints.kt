package com.xarvis.ai.faces

import android.graphics.Bitmap

/** Turns the faces in a photo into faceprints, left to right. Built from [FacePrintsProvider] (S22 build only). */
fun interface FacePrints {
    fun faces(photo: Bitmap): List<FloatArray>
}
