package dev.lumen.launcher

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

enum class Mode { Browse, Menu, Move }

class MenuItem(
    val label: String,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

/** Where a card lives: a position in the dock or in the grid. */
class Loc(val inDock: Boolean, val index: Int)

/** Things only the Activity can do. */
interface Host {
    fun open(entry: AppEntry, from: Rect?)
    fun openAppInfo(packageName: String)
    fun uninstall(packageName: String)
    fun playNav(dx: Int, dy: Int)
    fun playClick()
}

/**
 * The whole home screen: what is where, what is selected, and what each remote button does.
 *
 * Selection is tracked here rather than through the view focus system. A launcher that
 * loses focus is a launcher you cannot use, so nothing about navigation is left to chance.
 */
@Stable
class LauncherState(
    private val context: Context,
    private val scope: CoroutineScope,
    val metrics: Metrics,
    private val host: Host,
) {
    private val store = LayoutStore(context)
    private val iconPool = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val loading = HashSet<String>()
    private var known: Map<String, AppEntry> = emptyMap()
    private var hidden: Set<String> = emptySet()
    private var reloadJob: Job? = null
    private var okJob: Job? = null
    private var okArmed = false
    private var backArmed = false
    private var longFired = false
    private var lastMoveAt = 0L

    var dock by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var grid by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var mode by mutableStateOf(Mode.Browse)
        private set
    var pressed by mutableStateOf(false)
        private set
    var menuItems by mutableStateOf<List<MenuItem>>(emptyList())
        private set
    var menuIndex by mutableIntStateOf(0)
        private set
    var menuTarget by mutableStateOf<AppEntry?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set

    /** Direction of the last selection move, read by the card that just gained focus. */
    var lastDir: Pair<Int, Int> = Pair(0, 0)
        private set

    // ---------------------------------------------------------------- lookups

    fun entry(id: String?): AppEntry? = if (id == null) null else known[id]

    fun locate(id: String?): Loc? {
        if (id == null) return null
        val d = dock.indexOfFirst { it.id == id }
        if (d >= 0) return Loc(true, d)
        val g = grid.indexOfFirst { it.id == id }
        return if (g >= 0) Loc(false, g) else null
    }

    /** Top-left corner of a slot, before scrolling. */
    fun slotOf(loc: Loc): Pair<Float, Float> = if (loc.inDock) {
        Pair(metrics.slotX(loc.index), metrics.dockTop)
    } else {
        Pair(metrics.slotX(loc.index % Metrics.COLS), metrics.gridY(loc.index / Metrics.COLS))
    }

    /** How far the content is scrolled up when [loc] is selected. */
    fun scrollFor(loc: Loc?): Float {
        if (loc == null || loc.inDock) return 0f
        return (metrics.gridY(loc.index / Metrics.COLS) - metrics.anchor).coerceAtLeast(0f)
    }

    // ---------------------------------------------------------------- loading

    fun reload(debounceMs: Long = 0L) {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            if (debounceMs > 0L) delay(debounceMs)
            val previous = known
            val apps: List<AppEntry>? = try {
                withContext(Dispatchers.IO) { Apps.query(context, previous) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            if (apps != null) {
                applyApps(apps)
                loadIcons()
            }
            loaded = true
        }
    }

    /** Merges the installed apps with the saved layout. New apps join the end of the grid. */
    private fun applyApps(all: List<AppEntry>) {
        val byId = all.associateBy { it.id }
        known = byId
        val savedDock = store.read(LayoutStore.KEY_DOCK)
        val savedGrid = store.read(LayoutStore.KEY_GRID)
        hidden = store.read(LayoutStore.KEY_HIDDEN)?.toSet() ?: emptySet()
        val firstRun = savedDock == null && savedGrid == null

        val newDock = ArrayList<AppEntry>()
        val newGrid = ArrayList<AppEntry>()
        val used = HashSet<String>()

        if (firstRun) {
            for (id in Apps.DEFAULT_DOCK) {
                val e = byId[id] ?: continue
                if (newDock.size < Metrics.MAX_DOCK - 1 && used.add(id)) newDock.add(e)
            }
            val settings = byId[Apps.SETTINGS_ID]
            if (settings != null && used.add(settings.id)) newDock.add(settings)
        } else {
            for (id in savedDock ?: emptyList()) {
                val e = byId[id] ?: continue
                if (id !in hidden && newDock.size < Metrics.MAX_DOCK && used.add(id)) newDock.add(e)
            }
            for (id in savedGrid ?: emptyList()) {
                val e = byId[id] ?: continue
                if (id !in hidden && used.add(id)) newGrid.add(e)
            }
        }

        val fresh = all
            .filter { it.id !in used && it.id !in hidden }
            .sortedBy { it.label.lowercase() }
        newGrid.addAll(fresh)

        dock = newDock
        grid = newGrid
        if (locate(selectedId) == null) {
            selectedId = (newDock.firstOrNull() ?: newGrid.firstOrNull())?.id
        }
        if (mode == Mode.Menu && entry(menuTarget?.id) == null) closeMenu()
        if (firstRun || fresh.isNotEmpty()) persist()
    }

    private fun loadIcons() {
        for (e in dock + grid) {
            if (e.icon != null || !loading.add(e.id)) continue
            scope.launch {
                val result = withContext(iconPool) {
                    try {
                        IconLoader.load(context, e, metrics.iconW, metrics.iconH)
                    } catch (t: Throwable) {
                        null
                    }
                }
                loading.remove(e.id)
                if (result != null) {
                    e.icon = result.card
                    e.ambient = result.ambient
                }
            }
        }
    }

    private fun persist() {
        store.write(dock.map { it.id }, grid.map { it.id }, hidden)
    }

    fun destroy() {
        okJob?.cancel()
        reloadJob?.cancel()
        iconPool.close()
    }

    // ---------------------------------------------------------------- remote input

    /** Returns true when the key was ours. Everything else falls through to the system. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        if (!down && !up) return false
        val repeat = event.repeatCount > 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) nav(-1, 0, repeat, event.eventTime)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) nav(1, 0, repeat, event.eventTime)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) nav(0, -1, repeat, event.eventTime)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) nav(0, 1, repeat, event.eventTime)

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> if (down) {
                if (!repeat) okDown()
            } else {
                okUp()
            }

            KeyEvent.KEYCODE_MENU -> if (down && !repeat && mode == Mode.Browse && !pressed) {
                host.playClick()
                openMenu()
            }

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            -> if (down) {
                if (!repeat) backArmed = true
            } else if (backArmed) {
                // Only act on a Back press that started here, not one left over from an app closing.
                backArmed = false
                back()
            }

            else -> return false
        }
        return true
    }

    private fun nav(dx: Int, dy: Int, repeat: Boolean, time: Long) {
        // Holding a direction repeats ~20 times a second; glide at a calmer pace instead.
        if (repeat && time - lastMoveAt < REPEAT_INTERVAL_MS) return
        val moved = when (mode) {
            Mode.Browse -> if (pressed) false else moveSelection(dx, dy)
            Mode.Move -> moveItem(dx, dy)
            Mode.Menu -> moveMenu(dy)
        }
        if (moved) {
            lastMoveAt = time
            host.playNav(dx, dy)
        }
    }

    private fun okDown() {
        okArmed = true
        longFired = false
        if (mode != Mode.Browse || entry(selectedId) == null) return
        pressed = true
        okJob?.cancel()
        okJob = scope.launch {
            delay(LONG_PRESS_MS)
            if (pressed && mode == Mode.Browse) {
                longFired = true
                pressed = false
                host.playClick()
                openMenu()
            }
        }
    }

    private fun okUp() {
        if (!okArmed) return
        okArmed = false
        okJob?.cancel()
        if (longFired) {
            // This release belongs to the long press that opened the menu.
            longFired = false
            return
        }
        when (mode) {
            Mode.Browse -> if (pressed) {
                pressed = false
                val e = entry(selectedId)
                if (e != null) {
                    host.playClick()
                    activate(e)
                }
            }
            Mode.Menu -> {
                val item = menuItems.getOrNull(menuIndex)
                if (item != null) {
                    host.playClick()
                    item.action()
                }
            }
            Mode.Move -> {
                host.playClick()
                endMove()
            }
        }
    }

    private fun back() {
        when (mode) {
            Mode.Menu -> closeMenu()
            Mode.Move -> endMove()
            Mode.Browse -> {
                val first = dock.firstOrNull() ?: grid.firstOrNull()
                if (first != null && first.id != selectedId) {
                    lastDir = Pair(0, -1)
                    selectedId = first.id
                }
            }
        }
    }

    /** Home pressed while the launcher is already showing. */
    fun goHome() {
        resetTransient()
        when (mode) {
            Mode.Menu -> closeMenu()
            Mode.Move -> endMove()
            Mode.Browse -> back()
        }
    }

    /** Clears half-finished presses, for when another app takes over the screen. */
    fun resetTransient() {
        okJob?.cancel()
        pressed = false
        okArmed = false
        backArmed = false
        longFired = false
    }

    // ---------------------------------------------------------------- browsing

    private fun moveSelection(dx: Int, dy: Int): Boolean {
        val loc = locate(selectedId)
        if (loc == null) {
            val first = dock.firstOrNull() ?: grid.firstOrNull() ?: return false
            selectedId = first.id
            return true
        }
        val cols = Metrics.COLS
        var target: AppEntry? = null
        if (loc.inDock) {
            if (dx != 0) {
                target = dock.getOrNull(loc.index + dx)
            } else if (dy > 0) {
                target = grid.getOrNull(min(loc.index, min(cols, grid.size) - 1))
            }
        } else {
            val row = loc.index / cols
            val col = loc.index % cols
            if (dx != 0) {
                val nextCol = col + dx
                if (nextCol in 0 until cols) target = grid.getOrNull(loc.index + dx)
            } else if (dy > 0) {
                target = grid.getOrNull(loc.index + cols)
                // A short last row: drop to its final card rather than refusing to move.
                if (target == null && (grid.size - 1) / cols > row) target = grid.lastOrNull()
            } else {
                target = if (row == 0) {
                    dock.getOrNull(min(col, dock.size - 1))
                } else {
                    grid.getOrNull(loc.index - cols)
                }
            }
        }
        if (target == null) return false
        lastDir = Pair(dx, dy)
        selectedId = target.id
        return true
    }

    private fun activate(e: AppEntry) {
        host.open(e, boundsOf(e))
    }

    /** On-screen rectangle of a focused card, used as the origin of the launch animation. */
    private fun boundsOf(e: AppEntry): Rect? {
        val loc = locate(e.id) ?: return null
        val slot = slotOf(loc)
        val x = slot.first
        val y = slot.second - scrollFor(loc)
        val growX = metrics.cardW * (Metrics.FOCUS_SCALE - 1f) / 2f
        val growY = metrics.cardH * (Metrics.FOCUS_SCALE - 1f) / 2f
        return Rect(
            (x - growX).roundToInt(),
            (y - growY).roundToInt(),
            (x + metrics.cardW + growX).roundToInt(),
            (y + metrics.cardH + growY).roundToInt(),
        )
    }

    // ---------------------------------------------------------------- options menu

    private fun openMenu() {
        val e = entry(selectedId) ?: return
        val loc = locate(e.id) ?: return
        val items = ArrayList<MenuItem>()
        items.add(MenuItem("Open") { closeMenu(); activate(e) })
        items.add(MenuItem("Move") { mode = Mode.Move })
        if (loc.inDock) {
            items.add(MenuItem("Remove from Dock") { closeMenu(); undock(e) })
        } else if (dock.size < Metrics.MAX_DOCK) {
            items.add(MenuItem("Add to Dock") { closeMenu(); addToDock(e) })
        }
        if (!e.isBuiltin) {
            items.add(MenuItem("Hide") { closeMenu(); hide(e) })
            items.add(MenuItem("App Info") { closeMenu(); host.openAppInfo(e.packageName) })
            if (!e.isSystem) {
                items.add(MenuItem("Uninstall", destructive = true) { closeMenu(); host.uninstall(e.packageName) })
            }
        } else if (hidden.isNotEmpty()) {
            items.add(MenuItem("Show Hidden Apps (" + hidden.size + ")") { closeMenu(); unhideAll() })
        }
        menuTarget = e
        menuItems = items
        menuIndex = 0
        mode = Mode.Menu
    }

    private fun closeMenu() {
        if (mode == Mode.Menu) mode = Mode.Browse
    }

    private fun moveMenu(dy: Int): Boolean {
        if (dy == 0 || menuItems.isEmpty()) return false
        val next = (menuIndex + dy).coerceIn(0, menuItems.size - 1)
        if (next == menuIndex) return false
        menuIndex = next
        return true
    }

    private fun addToDock(e: AppEntry) {
        if (dock.size >= Metrics.MAX_DOCK) return
        grid = grid.filter { it.id != e.id }
        dock = dock.filter { it.id != e.id } + e
        persist()
    }

    private fun undock(e: AppEntry) {
        dock = dock.filter { it.id != e.id }
        grid = listOf(e) + grid.filter { it.id != e.id }
        persist()
    }

    private fun hide(e: AppEntry) {
        val loc = locate(e.id)
        var next: AppEntry? = null
        if (loc != null) {
            val list = if (loc.inDock) dock else grid
            next = list.getOrNull(loc.index + 1) ?: list.getOrNull(loc.index - 1)
        }
        dock = dock.filter { it.id != e.id }
        grid = grid.filter { it.id != e.id }
        hidden = hidden + e.id
        selectedId = (next ?: dock.firstOrNull() ?: grid.firstOrNull())?.id
        persist()
    }

    private fun unhideAll() {
        hidden = emptySet()
        persist()
        applyApps(known.values.toList())
        loadIcons()
    }

    // ---------------------------------------------------------------- rearranging

    private fun endMove() {
        if (mode == Mode.Move) mode = Mode.Browse
        persist()
    }

    /** Carries the selected card one step in a direction, across the dock/grid boundary too. */
    private fun moveItem(dx: Int, dy: Int): Boolean {
        val loc = locate(selectedId) ?: return false
        val cols = Metrics.COLS
        if (loc.inDock) {
            val d = ArrayList(dock)
            if (dx != 0) {
                val to = loc.index + dx
                if (to < 0 || to >= d.size) return false
                val e = d.removeAt(loc.index)
                d.add(to, e)
                dock = d
            } else if (dy > 0) {
                val e = d.removeAt(loc.index)
                val g = ArrayList(grid)
                g.add(min(loc.index, g.size), e)
                dock = d
                grid = g
            } else {
                return false
            }
        } else {
            val g = ArrayList(grid)
            val row = loc.index / cols
            val col = loc.index % cols
            if (dx != 0) {
                val to = loc.index + dx
                if (to < 0 || to >= g.size) return false
                val e = g.removeAt(loc.index)
                g.add(to, e)
                grid = g
            } else if (dy > 0) {
                val to = min(loc.index + cols, g.size - 1)
                if (to == loc.index) return false
                val e = g.removeAt(loc.index)
                g.add(to, e)
                grid = g
            } else if (row == 0) {
                if (dock.size >= Metrics.MAX_DOCK) return false
                val e = g.removeAt(loc.index)
                val d = ArrayList(dock)
                d.add(min(col, d.size), e)
                dock = d
                grid = g
            } else {
                val e = g.removeAt(loc.index)
                g.add(loc.index - cols, e)
                grid = g
            }
        }
        lastDir = Pair(dx, dy)
        // Saved at every step, so a refresh of the app list mid-move cannot undo it.
        persist()
        return true
    }

    companion object {
        private const val LONG_PRESS_MS = 480L
        private const val REPEAT_INTERVAL_MS = 95L
    }
}
