package com.yokhan.gates.windows

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import com.google.gson.Gson
import com.yokhan.gates.windows.qa.WindowsQaException
import com.yokhan.gates.windows.qa.WindowsQaServer
import com.yokhan.gates.windows.qa.WindowsQaState
import com.yokhan.gates.windows.qa.WindowsQaTarget
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryItem
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import org.jetbrains.skia.Image
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate

internal fun windowsQaRequested(args: Array<String>): Boolean {
    require(args.isEmpty() || args.first() == "--cli" ||
        (args.size == 1 && args.single() in setOf("--qa-ui", "--qa-access"))) { "Unknown launch arguments" }
    if (args.none { it == "--qa-ui" || it == "--qa-access" }) return false
    require(args.size == 1) { "Use the QA mode without other arguments" }
    return true
}

/** No runtime/controller reference is available to this capability. */
internal class WindowsQaSession(val realAccess: Boolean = false) : WindowsQaTarget, AutoCloseable {
    val profiling = mutableStateOf(false)
    val backgroundEffects = mutableStateOf(true)
    var motionDurationScale = 1f
    private var renderProbeInstalled = false
    private var capturing = false
    val timings = WindowsQaPerformance()
    val timingLabel: String get() = "${current.screen}/${current.connectionStatus}"
    private val semantics = WindowsQaSemantics()
    private val revisions = AtomicLong(0)
    private val monitor = Object()
    @Volatile private var current = WindowsQaState("starting", 0, true, emptyList())
    private val frames = WindowsQaFrames()
    @Volatile private var window: Window? = null
    private var perform: ((String) -> Unit)? = null
    @Volatile private var closed = false
    private val actionInProgress = AtomicBoolean(false)
    val sessionDirectory: Path = privateSessionDirectory()
    private val server = WindowsQaServer(this)
    @Volatile private var screenshotAllowed = true

    init {
        try {
            val descriptor = mapOf(
                "schema" to "gates-ui-qa-v1", "port" to server.port,
                "token" to server.token, "pid" to ProcessHandle.current().pid(),
                "mode" to if (realAccess) "access" else "fixture", "runtimeControl" to false,
            )
            Files.writeString(sessionDirectory.resolve("control.json"), Gson().toJson(descriptor))
            println("GATES_QA_SESSION=$sessionDirectory")
        } catch (failure: Exception) {
            server.close()
            throw failure
        }
    }

    fun attachWindow(value: Window) {
        window = value
        if (value is ComposeWindow) semantics.attach(value)
    }

    fun reserveRevision(actionRevision: Long): Long = revisions.updateAndGet { maxOf(it + 1, actionRevision) }

    fun publish(state: WindowsQaState, screenshotAllowed: Boolean = true, action: (String) -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        synchronized(monitor) {
            current = state
            this.screenshotAllowed = screenshotAllowed
            perform = action
            monitor.notifyAll()
        }
    }

    fun frameDrawn(revision: Long, surface: WindowsQaSurface = WindowsQaSurface.MAIN) {
        synchronized(monitor) {
            frames.drawn(surface, revision)
            monitor.notifyAll()
        }
    }

    override fun state(): WindowsQaState {
        if (closed || current.screen == "starting") throw WindowsQaException(503, "ui_not_ready")
        return onUi {
            val controls = semantics.controls()
            current.copy(actions = current.actions + controls.filter { it.clickable }.map { "click.${it.id}" } +
                (if (realAccess && Files.isRegularFile(sessionDirectory.resolve("manual-input.txt"), LinkOption.NOFOLLOW_LINKS)) listOf("input.access.private") else emptyList()) +
                listOf("perf.reset", "perf.stop", "viewport.compact", "viewport.default", "viewport.wide", "viewport.desktop", "scroll.forward", "scroll.back", "key.tab", "key.escape", "focus.first") +
                controls.filter { it.editable }.flatMap { control -> when (control.id) {
                    "report.message" -> listOf("input.report.sample", "input.report.clear")
                    "form.input.manual-input" -> listOf("input.access.sample", "input.access.clear")
                    "routing.search" -> listOf("input.routing.sample", "input.routing.clear", "input.routing.none")
                    "routing.manual-exe" -> listOf("input.exe.sample", "input.exe.invalid", "input.exe.clear")
                    else -> emptyList()
                } })
        }
    }

    override fun controls(): Any = onUi { semantics.controls() }
    override fun performance(): Any = onUi {
        val owner = window
        timings.snapshot() + mapOf("context" to mapOf(
            "windowActive" to (owner?.isActive == true),
            "windowFocused" to (owner?.isFocused == true),
            "windowShowing" to (owner?.isShowing == true),
            "renderer" to (owner?.let(::findSkiaLayer)?.renderApi?.toString() ?: "unavailable"),
            "backgroundEffects" to backgroundEffects.value,
            "motionDurationScale" to motionDurationScale,
            "renderProbeInstalled" to renderProbeInstalled,
            "displayRefreshRate" to (owner?.graphicsConfiguration?.device?.displayMode?.refreshRate ?: 0),
        ))
    }

    override fun action(id: String): WindowsQaState {
        if (realAccess && id.startsWith("fixture.")) throw WindowsQaException(403, "fixture_forbidden")
        if (!actionInProgress.compareAndSet(false, true)) throw WindowsQaException(503, "ui_busy")
        try {
            val previous = onUi {
                val before = state()
                if (id !in before.actions) throw WindowsQaException(409, "action_unavailable")
                timings.mark(id)
                when {
                    id.startsWith("click.") -> semantics.click(id.removePrefix("click."))
                    id.startsWith("input.") -> {
                        val tag = when (id.split('.')[1]) {
                            "report" -> "report.message"
                            "access" -> "form.input.manual-input"
                            "exe" -> "routing.manual-exe"
                            else -> "routing.search"
                        }
                        val privateInput = if (id == "input.access.private" && realAccess) {
                            val path = sessionDirectory.resolve("manual-input.txt")
                            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1..4096) {
                                throw WindowsQaException(409, "input_unavailable")
                            }
                            Files.readString(path).trim().also { subscriptionJsonEndpoint(it) }
                        } else null
                        semantics.input(tag, privateInput ?: if (id.endsWith(".clear")) "" else when (tag) {
                            "report.message" -> "QA synthetic report; not sent"
                            "form.input.manual-input" -> "https://example.invalid/sub/qa"
                            "routing.manual-exe" -> if (id.endsWith(".invalid")) "not-an-exe" else "test-app.exe"
                            else -> if (id.endsWith(".none")) "not-present" else "game"
                        })
                    }
                    id == "perf.reset" -> { installRenderProbe(); timings.reset(); profiling.value = true }
                    id == "perf.stop" -> { profiling.value = false; timings.stop() }
                    id == "scroll.forward" || id == "scroll.back" -> semantics.scroll(id == "scroll.forward")
                    id.startsWith("key.") -> semantics.key(window as? ComposeWindow
                        ?: throw WindowsQaException(409, "window_unavailable"), id == "key.tab")
                    id == "focus.first" -> {
                        window?.toFront()
                        window?.requestFocus()
                        semantics.focusFirst()
                    }
                    id.startsWith("viewport.") -> window?.size = when (id) {
                        "viewport.compact" -> java.awt.Dimension(360, 640)
                        "viewport.wide" -> java.awt.Dimension(760, 820)
                        "viewport.desktop" -> java.awt.Dimension(1180, 780)
                        else -> java.awt.Dimension(430, 820)
                    }
                    else -> perform?.invoke(id) ?: throw WindowsQaException(503, "ui_not_ready")
                }
                if (id.startsWith("click.") || id.startsWith("input.") || id.startsWith("perf.") ||
                    id.startsWith("viewport.") || id.startsWith("scroll.") || id.startsWith("key.") || id == "focus.first") perform?.invoke("qa.invalidate")
                before.revision
            }
            return awaitFrame { it.revision > previous }
        } finally {
            actionInProgress.set(false)
        }
    }

    override fun screenshot(): ByteArray {
        val expected = state().revision
        awaitFrame { it.revision >= expected }
        return onUi {
            if (!screenshotAllowed) throw WindowsQaException(409, "sensitive_input")
            if (!frames.ready(current)) throw WindowsQaException(409, "frame_pending")
            val owner = window?.takeIf { it.isShowing }
                ?: throw WindowsQaException(409, "window_not_visible")
            // Compose may render a dialog as a scene layer inside the owner,
            // not a separate AWT window. Both surfaces have current-frame gates.
            val surface = if (current.screen == "bug") {
                val owned = owner.ownedWindows.filter { it.isShowing }
                when (owned.size) {
                    0 -> owner
                    1 -> owned.single()
                    else -> throw WindowsQaException(409, "modal_not_ready")
                }
            } else owner
            val layer = findSkiaLayer(surface)
                ?: throw WindowsQaException(409, "renderer_unavailable")
            if (layer.width !in 1..2560 || layer.height !in 1..2560) {
                throw WindowsQaException(409, "window_size_out_of_bounds")
            }
            val renderer = layer.renderDelegate ?: throw WindowsQaException(409, "renderer_unavailable")
            // Capture the current scene, not a retained picture from a prior render.
            Bitmap().use {
                val width = (layer.width * layer.contentScale).toInt()
                val height = (layer.height * layer.contentScale).toInt()
                if (width !in 1..2560 || height !in 1..2560 || !it.allocN32Pixels(width, height)) {
                    throw WindowsQaException(409, "window_size_out_of_bounds")
                }
                capturing = true
                try { Canvas(it).use { canvas -> renderer.onRender(canvas, width, height, System.nanoTime()) } }
                finally { capturing = false }
                Image.makeFromBitmap(it).use { image ->
                    image.encodeToData()?.use { data -> data.bytes }
                        ?: throw WindowsQaException(409, "frame_not_ready")
                }
            }
        }
    }

    private fun installRenderProbe() {
        if (renderProbeInstalled) return
        val layer = window?.let(::findSkiaLayer) ?: throw WindowsQaException(409, "renderer_unavailable")
        val delegate = layer.renderDelegate ?: throw WindowsQaException(409, "renderer_unavailable")
        val threadMetrics = java.lang.management.ManagementFactory.getThreadMXBean()
        layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                val start = System.nanoTime()
                val cpuStart = if (threadMetrics.isCurrentThreadCpuTimeSupported && threadMetrics.isThreadCpuTimeEnabled)
                    threadMetrics.currentThreadCpuTime else -1L
                try { delegate.onRender(canvas, width, height, nanoTime) }
                finally {
                    if (!capturing) timings.scene(start, System.nanoTime() - start,
                        if (cpuStart >= 0) threadMetrics.currentThreadCpuTime - cpuStart else null, timingLabel)
                }
            }
        }
        renderProbeInstalled = true
    }

    private fun awaitFrame(ready: (WindowsQaState) -> Boolean): WindowsQaState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        synchronized(monitor) {
            while (!ready(current) || !frames.ready(current)) {
                if (closed) throw WindowsQaException(503, "qa_closed")
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw WindowsQaException(409, "frame_pending")
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
            return current
        }
    }

    private fun <T> onUi(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask(block)
        SwingUtilities.invokeLater(task)
        return try {
            task.get(3, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            task.cancel(false)
            throw WindowsQaException(503, "ui_timeout")
        } catch (wrapped: java.util.concurrent.ExecutionException) {
            throw (wrapped.cause as? WindowsQaException ?: WindowsQaException(503, "ui_unavailable"))
        }
    }

    override fun close() {
        closed = true
        synchronized(monitor) { monitor.notifyAll() }
        server.close()
    }
}

internal enum class WindowsQaSurface { MAIN, BUG }

internal class WindowsQaFrames {
    private val drawn = mutableMapOf<WindowsQaSurface, Long>()

    @Synchronized fun drawn(surface: WindowsQaSurface, revision: Long) {
        drawn[surface] = maxOf(drawn[surface] ?: -1, revision)
    }

    @Synchronized fun ready(state: WindowsQaState): Boolean =
        (drawn[WindowsQaSurface.MAIN] ?: -1) >= state.revision &&
            (state.screen != "bug" || (drawn[WindowsQaSurface.BUG] ?: -1) >= state.revision)
}

internal fun windowsQaIncidentItems(): List<GatesIncidentHistoryItem> = listOf(
    GatesIncidentHistoryItem("fixture-recovered", "15.10.2026 23:10", "Связь восстановилась", 12, false),
    GatesIncidentHistoryItem("fixture-interrupted", "15.10.2026 22:20", "Запись прервана", 7, true),
)

internal fun windowsQaRoutingChange(state: AppRoutingState, command: String, argument: String): AppRoutingState {
    if (command == "set-tun-routing") return state.copy(routeAll = argument == "all")
    val target = parseWindowsAppRoutingTarget(argument) ?: return state
    val config = updateWindowsAppRouting(WindowsAppConfig(
        appProxyProcessNames = state.proxyProcessNames, appProxyProcessPaths = state.proxyProcessPaths,
        appDirectProcessNames = state.directProcessNames, appDirectProcessPaths = state.directProcessPaths,
    ), target, proxy = command.endsWith("proxy"), remove = command.startsWith("remove-"))
    return state.copy(proxyProcessNames = config.appProxyProcessNames, proxyProcessPaths = config.appProxyProcessPaths,
        directProcessNames = config.appDirectProcessNames, directProcessPaths = config.appDirectProcessPaths)
}

private fun findSkiaLayer(component: Component): SkiaLayer? {
    if (component is SkiaLayer) return component
    return (component as? Container)?.components?.firstNotNullOfOrNull(::findSkiaLayer)
}

private fun privateSessionDirectory(): Path {
    var path = Path.of(System.getProperty("user.home"))
    for (part in listOf(".gates-qa", "sessions", UUID.randomUUID().toString())) {
        path = path.resolve(part)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
        check(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        if (acl != null) {
            acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(path)).setPermissions(*AclEntryPermission.entries.toTypedArray())
                .setFlags(java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,
                    java.nio.file.attribute.AclEntryFlag.FILE_INHERIT).build())
        } else {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
    }
    return path
}
