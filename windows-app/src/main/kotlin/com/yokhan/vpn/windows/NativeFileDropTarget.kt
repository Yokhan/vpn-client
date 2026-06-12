package com.yokhan.gates.windows

import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

class NativeFileDropTarget(
    private val component: Component,
    private val onExeFiles: (List<File>) -> Unit,
) {
    private var dropTarget: DropTarget? = null

    fun install() {
        if (dropTarget != null) return
        dropTarget = DropTarget(
            component,
            DnDConstants.ACTION_COPY,
            object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    val files = runCatching {
                        if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            event.rejectDrop()
                            return
                        }
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val dropped = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        dropped.filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
                    }.getOrElse {
                        event.rejectDrop()
                        return
                    }
                    if (files.isNotEmpty()) {
                        onExeFiles(files)
                    }
                    event.dropComplete(files.isNotEmpty())
                }
            },
            true,
        )
    }

    fun uninstall() {
        dropTarget = null
        runCatching { component.dropTarget = null }
    }
}
