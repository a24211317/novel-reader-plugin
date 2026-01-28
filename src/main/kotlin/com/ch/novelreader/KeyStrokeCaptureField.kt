package com.ch.novelreader

import com.intellij.ui.components.JBTextField
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * 在文本框中按下组合键，自动识别并显示（如 Ctrl+Alt+N），同时提供可保存的 strokeText（如 "ctrl alt N"）
 */
class KeyStrokeCaptureField(initialStrokeText: String = "") : JBTextField() {

    var strokeText: String = initialStrokeText
        private set

    init {
        isEditable = false
        focusTraversalKeysEnabled = false  // 允许 TAB 等按键被我们捕获（你也可按需开启/关闭）
        setFromStrokeText(initialStrokeText)

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                e.consume()

                // 只处理“有意义”的按键：不接受纯 modifier（只按 Ctrl / Shift）
                if (isPureModifier(e.keyCode)) return

                val mods = e.modifiersEx
                val ks = KeyStroke.getKeyStroke(e.keyCode, mods)
                strokeText = toStrokeText(ks)
                text = toHumanText(ks)
            }
        })
    }

    fun setFromStrokeText(stroke: String) {
        strokeText = stroke
        val ks = KeyStroke.getKeyStroke(stroke)
        text = if (ks != null) toHumanText(ks) else stroke
    }

    private fun isPureModifier(keyCode: Int): Boolean =
        keyCode == KeyEvent.VK_SHIFT || keyCode == KeyEvent.VK_CONTROL ||
                keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_META

    /** 保存用：KeyStroke.getKeyStroke(strokeText) 能解析 */
    private fun toStrokeText(ks: KeyStroke): String {
        val mods = ArrayList<String>()
        val m = ks.modifiers
        if (m and KeyEvent.CTRL_DOWN_MASK != 0) mods += "ctrl"
        if (m and KeyEvent.ALT_DOWN_MASK != 0) mods += "alt"
        if (m and KeyEvent.SHIFT_DOWN_MASK != 0) mods += "shift"
        if (m and KeyEvent.META_DOWN_MASK != 0) mods += "meta"

        // keyCode -> 文本（兼容 PAGE_DOWN 等）
        val key = KeyEvent.getKeyText(ks.keyCode).uppercase().replace(' ', '_')
        return (mods + key).joinToString(" ")
    }

    /** 展示用：更友好 */
    private fun toHumanText(ks: KeyStroke): String {
        val parts = ArrayList<String>()
        val m = ks.modifiers
        if (m and KeyEvent.CTRL_DOWN_MASK != 0) parts += "Ctrl"
        if (m and KeyEvent.ALT_DOWN_MASK != 0) parts += "Alt"
        if (m and KeyEvent.SHIFT_DOWN_MASK != 0) parts += "Shift"
        if (m and KeyEvent.META_DOWN_MASK != 0) parts += "Meta"
        parts += KeyEvent.getKeyText(ks.keyCode)
        return parts.joinToString("+")
    }
}
