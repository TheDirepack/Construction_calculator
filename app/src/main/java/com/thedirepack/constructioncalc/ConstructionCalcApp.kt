package com.thedirepack.constructioncalc

import android.app.Application
import com.thedirepack.constructioncalc.core.data.AppServices
import com.thedirepack.constructioncalc.shell.ToolRegistry

class ConstructionCalcApp : Application() {
    /** Shared repositories for the whole process. */
    val services: AppServices by lazy {
        val toolBackups = ToolRegistry.all.mapNotNull { tool -> tool.backup(this)?.let { tool.id to it } }.toMap()
        AppServices.create(this, toolBackups)
    }
}
