package dev.lumen.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONArray

/** One card on the home screen. */
@Stable
class AppEntry(
    val id: String,
    val packageName: String,
    val label: String,
    /** Null for the built-in Settings card. */
    val component: ComponentName?,
    val isSystem: Boolean,
    val leanback: Boolean,
    /** Last update time of the package; part of the icon cache key. */
    val stamp: Long,
) {
    var icon by mutableStateOf<ImageBitmap?>(null)
    var ambient by mutableStateOf<ImageBitmap?>(null)

    val isBuiltin: Boolean get() = component == null
}

object Apps {
    const val SETTINGS_ID = "builtin.settings"

    /** Apps that go in the dock on first run, when installed. */
    val DEFAULT_DOCK: List<String> = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom",
        "com.hulu.livingroomplus",
        "com.wbd.stream",
        "com.plexapp.android",
        "com.google.android.youtube.tvunplugged",
        "com.spotify.tv.android",
    )

    /**
     * Every launchable app: TV apps first, then sideloaded apps that only declare a phone
     * launcher entry. Entries that have not changed are reused so their icons stay loaded.
     */
    @Suppress("DEPRECATION")
    fun query(context: Context, previous: Map<String, AppEntry>): List<AppEntry> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, AppEntry>()

        fun collect(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val activities: List<ResolveInfo> = try {
                pm.queryIntentActivities(intent, 0)
            } catch (e: Throwable) {
                emptyList<ResolveInfo>()
            }
            for (info in activities) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName ?: continue
                if (pkg == context.packageName || found.containsKey(pkg)) continue

                val stamp = try {
                    pm.getPackageInfo(pkg, 0).lastUpdateTime
                } catch (e: Throwable) {
                    0L
                }
                val component = ComponentName(pkg, activity.name)
                val old = previous[pkg]
                if (old != null && old.stamp == stamp && old.component == component) {
                    found[pkg] = old
                    continue
                }

                val label = try {
                    info.loadLabel(pm)?.toString()
                } catch (e: Throwable) {
                    null
                }
                val flags = activity.applicationInfo?.flags ?: 0
                found[pkg] = AppEntry(
                    id = pkg,
                    packageName = pkg,
                    label = if (label.isNullOrBlank()) pkg else label,
                    component = component,
                    isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    leanback = leanback,
                    stamp = stamp,
                )
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)

        val all = ArrayList<AppEntry>(found.values)
        all.add(
            previous[SETTINGS_ID] ?: AppEntry(
                id = SETTINGS_ID,
                packageName = "settings",
                label = "Settings",
                component = null,
                isSystem = true,
                leanback = true,
                stamp = 0L,
            ),
        )
        return all
    }
}

/** Saved order of the dock and grid, plus hidden apps. */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)

    fun read(key: String): List<String>? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        } catch (e: Throwable) {
            null
        }
    }

    fun write(dock: List<String>, grid: List<String>, hidden: Collection<String>) {
        prefs.edit()
            .putString(KEY_DOCK, toJson(dock))
            .putString(KEY_GRID, toJson(grid))
            .putString(KEY_HIDDEN, toJson(hidden))
            .apply()
    }

    private fun toJson(items: Collection<String>): String {
        val array = JSONArray()
        for (item in items) array.put(item)
        return array.toString()
    }

    companion object {
        const val KEY_DOCK = "dock"
        const val KEY_GRID = "grid"
        const val KEY_HIDDEN = "hidden"
    }
}
