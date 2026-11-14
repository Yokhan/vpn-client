package com.yokhan.gates.windows

import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.SwingUtilities

private const val SUPPORTED_DROP_ACTIONS =
    DnDConstants.ACTION_COPY_OR_MOVE or DnDConstants.ACTION_LINK

class NativeFileDropTarget(
    private val component: Component,
    private val onDragLocationChanged: (Point?) -> Unit = {},
    private val onFilesDropped: (List<File>, Point) -> Unit,
) {
    constructor(
        component: Component,
        onAppFiles: (List<File>) -> Unit,
    ) : this(
        component = component,
        onDragLocationChanged = {},
        onFilesDropped = { files, _ ->
            val filtered = files.filter { it.isFile && isWindowsAppTargetPath(it.absolutePath) }
            if (filtered.isNotEmpty()) onAppFiles(filtered)
        },
    )

    private val attachedTargets = IdentityHashMap<Component, DropTarget>()
    private val containerListener = object : ContainerAdapter() {
        override fun componentAdded(e: ContainerEvent) {
            attachRecursively(e.child)
        }

        override fun componentRemoved(e: ContainerEvent) {
            detachRecursively(e.child)
        }
    }
    private val watchedContainers = Collections.newSetFromMap(IdentityHashMap<Container, Boolean>())
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        attachRecursively(component)
    }

    fun uninstall() {
        if (!installed) return
        installed = false
        onDragLocationChanged(null)
        detachRecursively(component)
        watchedContainers.toList().forEach { container ->
            runCatching { container.removeContainerListener(containerListener) }
        }
        watchedContainers.clear()
        attachedTargets.keys.toList().forEach { targetComp ->
            runCatching { targetComp.dropTarget = null }
        }
        attachedTargets.clear()
    }

    private fun toRootPoint(source: Component, point: Point): Point {
        if (source === component) return Point(point)
        return runCatching {
            SwingUtilities.convertPoint(source, point, component)
        }.getOrElse { Point(point) }
    }

    private fun attachRecursively(target: Component?) {
        if (target == null || !installed) return
        if (!attachedTargets.containsKey(target)) {
            val dt = DropTarget(
                target,
                SUPPORTED_DROP_ACTIONS,
                object : DropTargetAdapter() {
                    override fun dragEnter(event: DropTargetDragEvent) {
                        if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            event.acceptDrag(chooseDragAction(event.dropAction))
                            onDragLocationChanged(toRootPoint(target, event.location))
                        } else {
                            event.rejectDrag()
                        }
                    }

                    override fun dragOver(event: DropTargetDragEvent) {
                        if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            event.acceptDrag(chooseDragAction(event.dropAction))
                            onDragLocationChanged(toRootPoint(target, event.location))
                        } else {
                            event.rejectDrag()
                        }
                    }

                    override fun dropActionChanged(event: DropTargetDragEvent) {
                        if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            event.acceptDrag(chooseDragAction(event.dropAction))
                        } else {
                            event.rejectDrag()
                        }
                    }

                    override fun dragExit(event: DropTargetEvent) {
                        onDragLocationChanged(null)
                    }

                    override fun drop(event: DropTargetDropEvent) {
                        onDragLocationChanged(null)
                        val files = runCatching {
                            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                event.rejectDrop()
                                return
                            }
                            event.acceptDrop(chooseDragAction(event.dropAction))
                            @Suppress("UNCHECKED_CAST")
                            (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                                .filter { it.exists() }
                        }.getOrElse {
                            event.rejectDrop()
                            return
                        }
                        val rootPoint = toRootPoint(target, event.location)
                        if (files.isNotEmpty()) {
                            onFilesDropped(files, rootPoint)
                        }
                        event.dropComplete(files.isNotEmpty())
                    }
                },
                true,
            )
            attachedTargets[target] = dt
        }
        if (target is Container) {
            if (watchedContainers.add(target)) {
                target.addContainerListener(containerListener)
            }
            target.components.forEach { child -> attachRecursively(child) }
        }
    }

    private fun detachRecursively(target: Component?) {
        if (target == null) return
        attachedTargets.remove(target)?.let {
            runCatching { target.dropTarget = null }
        }
        if (target is Container) {
            if (watchedContainers.remove(target)) {
                runCatching { target.removeContainerListener(containerListener) }
            }
            target.components.forEach { child -> detachRecursively(child) }
        }
    }

    private fun chooseDragAction(requestedAction: Int): Int {
        val masked = requestedAction and SUPPORTED_DROP_ACTIONS
        return if (masked != 0) masked else DnDConstants.ACTION_COPY
    }
}
