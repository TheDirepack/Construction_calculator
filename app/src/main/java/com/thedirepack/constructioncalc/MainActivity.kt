package com.thedirepack.constructioncalc

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.compositionContext
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.thedirepack.constructioncalc.core.data.ProjectSharing
import com.thedirepack.constructioncalc.core.data.ThemeMode
import com.thedirepack.constructioncalc.core.model.AppFile
import com.thedirepack.constructioncalc.core.ui.theme.ConstructionCalcTheme
import com.thedirepack.constructioncalc.core.ui.tool.LocalAppServices
import com.thedirepack.constructioncalc.core.ui.tool.LocalTools
import com.thedirepack.constructioncalc.shell.AppShell
import com.thedirepack.constructioncalc.shell.QuickSetupScreen
import com.thedirepack.constructioncalc.shell.ToolRegistry
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /**
     * Project or backup files opened with / shared to the app, for the shell to confirm. A channel,
     * not a SharedFlow: a file read before the shell starts collecting (cold start from "open with")
     * waits in the buffer instead of being dropped.
     */
    private val imports = Channel<Result<AppFile>>(Channel.BUFFERED)
    private val importFlow = imports.receiveAsFlow()

    /** Scales every Compose animation: 1 normally, 0 when the user turns animations off. */
    private val motion = object : MotionDurationScale {
        @Volatile var enabled = true
        override val scaleFactor: Float get() = if (enabled) 1f else 0f
    }

    @OptIn(InternalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val services = (application as ConstructionCalcApp).services

        // All composition in this window runs with [motion] in its coroutine context, so the
        // "Animations" setting affects sheets, menus and transitions everywhere.
        window.decorView.compositionContext = window.decorView.createLifecycleAwareWindowRecomposer(motion, lifecycle)
        services.settings.settings.onEach { motion.enabled = it.animations }.launchIn(lifecycleScope)

        setContent {
            val settings by services.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's theme, not the system's: dark icons on
            // a light app, light icons on a dark app, even when the app overrides the system mode.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ConstructionCalcTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppServices provides services, LocalTools provides ToolRegistry.all) {
                    if (settings.setupDone) {
                        AppShell(imports = importFlow)
                    } else {
                        QuickSetupScreen(onDone = { services.settings.setSetupDone(true) })
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Reads a project or backup file opened with or shared to the app; the shell asks what to do. */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return
        lifecycleScope.launch {
            imports.send(runCatching { withContext(Dispatchers.IO) { ProjectSharing.readAppFile(this@MainActivity, uri) } })
        }
    }
}
