package com.ch.novelreader

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class NovelReaderConfigurable : Configurable {

    private var panel: JPanel? = null

    private val fileField = TextFieldWithBrowseButton().apply {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor().apply {
            title = "选择小说文件"
            description = "请选择一个本地 txt 小说文件"
        }
        addBrowseFolderListener(null, descriptor)
    }

    private val fontSpinner = JSpinner(SpinnerNumberModel(16, 10, 64, 1))
    private val bottomBarCheck = JBCheckBox()

    override fun getDisplayName(): String = "小说阅读器"

    override fun createComponent(): JComponent {
        val root = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
        }

        fun row(label: String, comp: JComponent) {
            c.gridx = 0; c.weightx = 0.0
            root.add(JBLabel(label), c)
            c.gridx = 1; c.weightx = 1.0
            root.add(comp, c)
            c.gridy++
        }

        row("小说文件：", fileField)
        row("字号：", fontSpinner)
        row("单行模式：", bottomBarCheck)

        panel = root
        reset()
        return root
    }

    override fun isModified(): Boolean {
        val s = NovelReaderState.getInstance().state
        return fileField.text.trim() != s.novelFilePath ||
                (fontSpinner.value as Int) != s.fontSize ||
                bottomBarCheck.isSelected != s.showInBottomBar
    }

    override fun apply() {
        val st = NovelReaderState.getInstance().state
        st.novelFilePath = fileField.text.trim()
        st.fontSize = (fontSpinner.value as Int)
        st.showInBottomBar = bottomBarCheck.isSelected

        ProjectManager.getInstance().openProjects.forEach { p ->
            p.messageBus.syncPublisher(ReaderTopics.SETTINGS_CHANGED).settingsChanged()
        }
    }

    override fun reset() {
        val s = NovelReaderState.getInstance().state
        fileField.text = s.novelFilePath
        fontSpinner.value = s.fontSize
        bottomBarCheck.isSelected = s.showInBottomBar
    }

    override fun disposeUIResources() {
        panel = null
    }
}
