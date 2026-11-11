package dev.lumen.launcher

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.SoundEffectConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity(), Host {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var state: LauncherState? = null
    private var onScreen = false
    private var receiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Installs and updates arrive as a burst of broadcasts; coalesce them.
            state?.reload(debounceMs = 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dm = resources.displayMetrics
        val fontScale = resources.configuration.fontScale
        val metrics = Metrics(
            screenW = max(dm.widthPixels, dm.heightPixels).toFloat(),
            screenH = min(dm.widthPixels, dm.heightPixels).toFloat(),
            density = if (dm.density > 0f) dm.density else 1f,
            fontScale = if (fontScale > 0f) fontScale else 1f,
        )
        val launcher = LauncherState(applicationContext, scope, metrics, this)
        state = launcher
        launcher.reload()

        try {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_PACKAGE_ADDED)
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED)
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
            filter.addDataScheme("package")
            ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: Throwable) {
            // Without the receiver the list still refreshes every time Home is shown.
        }

        setContent { HomeScreen(launcher) }
    }

    override fun onStart() {
        super.onStart()
        onScreen = true
        // Catches anything installed or removed while a broadcast was missed.
        if (state?.loaded == true) state?.reload()
    }

    override fun onPause() {
        state?.resetTransient()
        super.onPause()
    }

    override fun onStop() {
        onScreen = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while Home is already showing: back to the first app, like tvOS.
        // Coming home from another app keeps your place instead. Android pauses an activity
        // before handing it a new intent, so "already showing" means started and holding
        // window focus, not resumed.
        if (onScreen && hasWindowFocus() && intent.hasCategory(Intent.CATEGORY_HOME)) {
            state?.goHome()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(packageReceiver)
            } catch (e: Throwable) {
                // Already gone.
            }
        }
        state?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Remote buttons are handled here, ahead of the view hierarchy, so the launcher keeps
     * responding even if nothing on screen holds focus.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = try {
            state?.onKey(event) ?: false
        } catch (e: Throwable) {
            false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- Host

    override fun open(entry: AppEntry, from: Rect?) {
        val options: Bundle? = try {
            if (from == null) {
                null
            } else {
                ActivityOptions.makeScaleUpAnimation(
                    window.decorView, from.left, from.top, from.width(), from.height(),
                ).toBundle()
            }
        } catch (e: Throwable) {
            null
        }

        val primary = if (entry.isBuiltin) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            Intent(Intent.ACTION_MAIN)
                .addCategory(if (entry.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
        }
        primary.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (start(primary, options)) return

        if (!entry.isBuiltin) {
            val fallback = try {
                packageManager.getLeanbackLaunchIntentForPackage(entry.packageName)
                    ?: packageManager.getLaunchIntentForPackage(entry.packageName)
            } catch (e: Throwable) {
                null
            }
            if (fallback != null && start(fallback, options)) return
        }
        toast("Couldn't open " + entry.label)
    }

    override fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(intent, null)) toast("App info isn't available")
    }

    override fun uninstall(packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(intent, null)) toast("Couldn't start uninstall")
    }

    override fun playNav(dx: Int, dy: Int) {
        val effect = when {
            dx < 0 -> SoundEffectConstants.NAVIGATION_LEFT
            dx > 0 -> SoundEffectConstants.NAVIGATION_RIGHT
            dy < 0 -> SoundEffectConstants.NAVIGATION_UP
            else -> SoundEffectConstants.NAVIGATION_DOWN
        }
        playEffect(effect)
    }

    override fun playClick() {
        playEffect(SoundEffectConstants.CLICK)
    }

    private fun playEffect(effect: Int) {
        try {
            window.decorView.playSoundEffect(effect)
        } catch (e: Throwable) {
            // Sound is a nicety.
        }
    }

    private fun start(intent: Intent, options: Bundle?): Boolean = try {
        startActivity(intent, options)
        true
    } catch (e: Throwable) {
        false
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            // Nothing else to do.
        }
    }
}
