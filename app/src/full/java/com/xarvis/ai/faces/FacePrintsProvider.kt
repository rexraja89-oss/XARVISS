package com.xarvis.ai.faces

import android.content.Context

/** The S22 build has face recognition: one shared engine, made on first use. */
object FacePrintsProvider {
    @Volatile private var engine: FaceEngine? = null

    fun create(context: Context): FacePrints? {
        val e = engine ?: synchronized(this) { engine ?: FaceEngine(context.applicationContext).also { engine = it } }
        return FacePrints { photo -> e.faces(photo).map { it.print } }
    }
}
