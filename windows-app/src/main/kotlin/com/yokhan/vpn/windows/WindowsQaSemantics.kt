@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.yokhan.gates.windows

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.input.key.toComposeEvent
import com.yokhan.gates.windows.qa.WindowsQaException

internal data class WindowsQaControl(
    val id: String, val enabled: Boolean, val clickable: Boolean, val editable: Boolean,
    val left: Float, val top: Float, val width: Float, val height: Float,
    val focused: Boolean = false,
    val role: String = "",
    val selected: Boolean? = null,
)

/** Version-pinned Compose test hook, attached only to our isolated QA window. */
internal class WindowsQaSemantics : PlatformContext.RootForTestListener {
    private val roots = linkedSetOf<PlatformRootForTest>()

    fun attach(window: ComposeWindow) { window.rootForTestListener = this }
    override fun onRootForTestCreated(root: PlatformRootForTest) { roots += root }
    override fun onRootForTestDisposed(root: PlatformRootForTest) { roots -= root }

    fun controls(): List<WindowsQaControl> = nodes().mapNotNull { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag) ?: return@mapNotNull null
        if (!isWindowsQaTag(tag)) return@mapNotNull null
        val position = node.positionInWindow
        val size = node.size
        WindowsQaControl(tag, !node.config.contains(SemanticsProperties.Disabled),
            node.config.contains(SemanticsActions.OnClick), node.config.contains(SemanticsActions.SetText),
            position.x, position.y, size.width.toFloat(), size.height.toFloat(),
            node.config.getOrNull(SemanticsProperties.Focused) == true,
            node.config.getOrNull(SemanticsProperties.Role)?.toString().orEmpty(),
            node.config.getOrNull(SemanticsProperties.Selected))
    }.distinctBy { it.id }.take(256)

    fun click(tag: String) {
        val node = find(tag)
        if (node.config.contains(SemanticsProperties.Disabled)) throw WindowsQaException(409, "control_disabled")
        val click = node.config.getOrNull(SemanticsActions.OnClick)?.action
            ?: throw WindowsQaException(409, "not_clickable")
        if (!click()) throw WindowsQaException(409, "action_rejected")
    }

    fun input(tag: String, text: String) {
        val node = find(tag)
        if (node.config.contains(SemanticsProperties.Disabled)) throw WindowsQaException(409, "control_disabled")
        val setText = node.config.getOrNull(SemanticsActions.SetText)?.action
            ?: throw WindowsQaException(409, "not_editable")
        if (!setText(AnnotatedString(text))) throw WindowsQaException(409, "action_rejected")
    }

    fun scroll(forward: Boolean) {
        val node = nodes().firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
            ?: throw WindowsQaException(409, "not_scrollable")
        if (node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, if (forward) 420f else -420f) != true) {
            throw WindowsQaException(409, "action_rejected")
        }
    }

    fun key(window: ComposeWindow, tab: Boolean) {
        val code = if (tab) java.awt.event.KeyEvent.VK_TAB else java.awt.event.KeyEvent.VK_ESCAPE
        for (kind in listOf(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_RELEASED)) {
            val event = java.awt.event.KeyEvent(window, kind, System.currentTimeMillis(), 0, code,
                java.awt.event.KeyEvent.CHAR_UNDEFINED).toComposeEvent()
            roots.lastOrNull()?.sendKeyEvent(event)
                ?: throw WindowsQaException(409, "root_unavailable")
        }
    }

    fun focusFirst() {
        val node = nodes().firstOrNull {
            it.config.getOrNull(SemanticsProperties.TestTag)?.let(::isWindowsQaTag) == true &&
                !it.config.contains(SemanticsProperties.Disabled) && it.config.contains(SemanticsActions.RequestFocus)
        } ?: throw WindowsQaException(409, "focus_unavailable")
        if (node.config.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() != true) {
            throw WindowsQaException(409, "focus_rejected")
        }
    }

    private fun find(tag: String): SemanticsNode {
        if (!isWindowsQaTag(tag)) throw WindowsQaException(403, "control_forbidden")
        return nodes().singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
            ?: throw WindowsQaException(409, "control_unavailable")
    }

    private fun nodes(): List<SemanticsNode> {
        val result = ArrayList<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            if (result.size >= 2048) return
            result += node
            node.children.forEach(::visit)
        }
        roots.forEach { visit(it.semanticsOwner.unmergedRootSemanticsNode) }
        return result
    }
}

internal fun isWindowsQaTag(tag: String): Boolean =
    tag.length in 1..72 &&
        tag.matches(Regex("(?:nav|dashboard|access|settings|routes|routing|drawer|logs|tools|report|route|form|history)\\.[a-z0-9_.-]+"))
