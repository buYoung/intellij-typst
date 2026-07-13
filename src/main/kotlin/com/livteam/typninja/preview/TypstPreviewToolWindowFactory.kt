package com.livteam.typninja.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.ImageIcon
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

class TypstPreviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val pagePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Save the Typst file, then run Preview."))
        }
        val panel = JPanel(BorderLayout()).apply { add(JScrollPane(pagePanel), BorderLayout.CENTER) }
        val removeListener = TypstPreviewService.getInstance(project).addListener { result ->
            SwingUtilities.invokeLater {
                pagePanel.removeAll()
                if (result.failureMessage != null) pagePanel.add(JLabel(result.failureMessage))
                else if (result.outputFiles.isNotEmpty() && result.format == "png") {
                    result.outputFiles.forEachIndexed { pageIndex, output ->
                        pagePanel.add(JLabel(ImageIcon(output.path)).apply { text = "Page ${pageIndex + 1}" })
                    }
                } else pagePanel.add(JLabel("Typst output created: ${result.outputFile?.path.orEmpty()}"))
                pagePanel.revalidate()
                pagePanel.repaint()
            }
        }
        val content = ContentFactory.getInstance().createContent(panel, "Preview", false)
        Disposer.register(content, Disposable { removeListener() })
        toolWindow.contentManager.addContent(content)
    }
}
