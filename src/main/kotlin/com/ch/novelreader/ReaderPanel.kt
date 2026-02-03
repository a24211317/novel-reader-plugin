package com.ch.novelreader

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.AbstractListModel
import javax.swing.JToggleButton
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.ListSelectionEvent
import javax.swing.text.BadLocationException
import javax.swing.text.Document
import kotlin.io.path.exists

data class Chapter(val title: String, val file: Path)

class ReaderPanel(private val project: Project) : JBPanel<ReaderPanel>(BorderLayout()) {

    // -------- UI: right text --------
    private val textArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
    }
    private val textScroll = JBScrollPane(textArea).apply {
        isWheelScrollingEnabled = true
    }

    // -------- UI: left chapters --------
    private val chapterModel = ChapterListModel()
    private val chapterList = JBList(chapterModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        addListSelectionListener { e: ListSelectionEvent ->
            if (!e.valueIsAdjusting) {
                val idx = selectedIndex
                if (idx >= 0) onChapterSelected(idx)
            }
        }
    }
    private val chapterScroll = JBScrollPane(chapterList)

    // -------- Splitter --------
    private val splitter = OnePixelSplitter(false, 0.25f).apply {
        firstComponent = chapterScroll
        secondComponent = textScroll
    }

    // -------- Top-left: catalog toggle --------
    private val btnToggleCatalog = JToggleButton("隐藏目录", true)
    private val topLeftBar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        add(btnToggleCatalog)
    }

    // -------- Chapter cache --------
    private var cacheDir: Path? = null
    private var currentNovelPath: String = ""
    private val CHAPTER_RE = Regex("^\\s*(第[0-9一二三四五六七八九十百千两〇零]+[章节回卷部篇].*)\\s*$")

    // -------- Auto-load by sustained scrolling (down/up) --------
    private val AUTO_WHEEL_COUNT = 3
    private val AUTO_WINDOW_MS = 900L

    private val BOTTOM_THRESHOLD = 16
    private val TOP_THRESHOLD = 2

    private var downCountAtBottom = 0
    private var lastDownTime = 0L

    private var upCountAtTop = 0
    private var lastUpTime = 0L

    // -------- Append + prepend + sliding window --------
    private val MAX_KEEP_CHAPTERS = 8

    /** 当前文本中已加载章节范围 [loadedStart, loadedEnd]（含） */
    private var loadedStart: Int = -1
    private var loadedEnd: Int = -1

    /** 每章在 document 中的 start/end（不在窗口内则 -1） */
    private var chapterStartOffsets: IntArray = IntArray(0) { -1 }
    private var chapterEndOffsets: IntArray = IntArray(0) { -1 }

    private var suppressSelection = false

    // -------- Reading position persistence (throttled) --------
    private var saveTimer: Timer? = null

    init {
        val root = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(topLeftBar, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
        add(root, BorderLayout.CENTER)

        btnToggleCatalog.addActionListener {
            val show = btnToggleCatalog.isSelected
            btnToggleCatalog.text = if (show) "隐藏目录" else "显示目录"
            splitter.firstComponent.isVisible = show
            splitter.revalidate()
            splitter.repaint()
        }

        project.messageBus.connect().subscribe(ReaderTopics.SETTINGS_CHANGED, object : ReaderSettingsListener {
            override fun settingsChanged() {
                refreshFromSettings()
            }
        })

        installSustainedScrollAutoLoad()
        installReadingPositionSaver()
        refreshFromSettings()
    }

    fun refreshFromSettings() {
        val s = NovelReaderState.getInstance().state
        textArea.font = Font(Font.SANS_SERIF, Font.PLAIN, s.fontSize)

        val path = s.novelFilePath.trim()
        if (path.isBlank()) {
            currentNovelPath = ""
            chapterModel.setChapters(emptyList())
            resetAllState()
            setUiText("请在【设置 -> 小说阅读器】中选择小说文件。")
            return
        }

        val f = File(path)
        if (!f.exists() || !f.isFile) {
            currentNovelPath = ""
            chapterModel.setChapters(emptyList())
            resetAllState()
            setUiText("文件不存在：$path")
            return
        }

        if (currentNovelPath == path && chapterModel.getSize() > 0) return

        currentNovelPath = path
        chapterModel.setChapters(emptyList())
        resetAllState()
        setUiText("正在分析章节...\n文件：${f.absolutePath}\n大小：${f.length() / 1024} KB")

        splitIntoChaptersAsync(f.toPath(), Charsets.UTF_8)
    }

    // ---------------- splitting ----------------

    private fun splitIntoChaptersAsync(novelPath: Path, charset: Charset) {
        cacheDir = Files.createTempDirectory("novel_reader_chapters_")
        val root = Path.of(PathManager.getSystemPath(), "novel-reader", "chapters")
        if (!root.exists()) Files.createDirectories(root)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "解析章节", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val chapters = ArrayList<Chapter>()
                var currentWriter: BufferedWriter? = null
                var chapterIndex = 0

                fun openNewChapter(title: String) {
                    currentWriter?.close()
                    val file = cacheDir!!.resolve(String.format("%05d.txt", chapterIndex++))
                    currentWriter = Files.newBufferedWriter(file, charset)
                    chapters.add(Chapter(title, file))
                    publishChapters(chapters.toList())
                }

                try {
                    openNewChapter("开篇")

                    Files.newBufferedReader(novelPath, charset).use { br: BufferedReader ->
                        while (true) {
                            val line = br.readLine() ?: break
                            val m = CHAPTER_RE.matchEntire(line)
                            if (m != null) {
                                openNewChapter(m.groupValues[1])
                                continue
                            }
                            currentWriter?.write(line)
                            currentWriter?.newLine()
                        }
                    }

                    currentWriter?.close()

                    SwingUtilities.invokeLater {
                        if (chapterModel.getSize() > 0 && chapterList.selectedIndex < 0) {
                            val st = NovelReaderState.getInstance().state
                            val restoreIdx = st.lastChapterIndex.coerceIn(0, chapterModel.getSize() - 1)
                            syncListSelection(restoreIdx)
                            startFromChapter(restoreIdx, tryRestorePos = true)
                        }
                    }
                } catch (e: Exception) {
                    try { currentWriter?.close() } catch (_: Exception) {}
                    setUiText("章节解析失败：${e.message}")
                }
            }
        })
    }

    private fun publishChapters(list: List<Chapter>) {
        SwingUtilities.invokeLater {
            chapterModel.setChapters(list)
            chapterStartOffsets = IntArray(list.size) { -1 }
            chapterEndOffsets = IntArray(list.size) { -1 }
            resetAppendStateOnly()
        }
    }

    // ---------------- selection ----------------

    private fun onChapterSelected(index: Int) {
        if (suppressSelection) return

        val start = chapterStartOffsets.getOrNull(index) ?: -1
        if (start >= 0 && index in loadedStart..loadedEnd) {
            jumpToOffsetStable(start)
            syncListSelection(index)
            scheduleSaveReadingPos()
            return
        }

        if (loadedEnd >= 0 && index == loadedEnd + 1) {
            appendChapter(index, jumpToTitle = true, keepReadingAnchor = false)
            return
        }
        if (loadedStart >= 0 && index == loadedStart - 1) {
            prependChapter(index, jumpToTitle = true)
            return
        }

        startFromChapter(index, tryRestorePos = false)
    }

    private fun syncListSelection(idx: Int) {
        if (idx !in 0 until chapterModel.getSize()) return
        suppressSelection = true
        chapterList.selectedIndex = idx
        chapterList.ensureIndexIsVisible(idx)
        suppressSelection = false
    }

    // ---------------- current chapter by view (for save + sync) ----------------

    private fun currentChapterIndexFromView(): Int {
        if (loadedStart < 0 || loadedEnd < 0) return chapterList.selectedIndex.coerceAtLeast(0)
        val docOffset = topVisibleDocOffset() ?: return chapterList.selectedIndex.coerceAtLeast(0)

        for (i in loadedStart..loadedEnd) {
            val s = chapterStartOffsets.getOrNull(i) ?: -1
            val e = chapterEndOffsets.getOrNull(i) ?: -1
            if (s >= 0 && e > s && docOffset in s until e) return i
        }
        return loadedEnd
    }

    private fun topVisibleDocOffset(): Int? {
        val p = textScroll.viewport.viewPosition
        return try { textArea.viewToModel2D(p) } catch (_: Exception) { null }
    }

    // ---------------- start / append / prepend ----------------

    private fun startFromChapter(index: Int, tryRestorePos: Boolean) {
        if (index !in 0 until chapterModel.getSize()) return
        resetAutoCounters()

        SwingUtilities.invokeLater {
            textArea.text = ""
            textScroll.verticalScrollBar.value = 0
        }

        for (i in chapterStartOffsets.indices) {
            chapterStartOffsets[i] = -1
            chapterEndOffsets[i] = -1
        }

        loadedStart = index
        loadedEnd = index
        syncListSelection(index)

        loadAndAppendIntoEnd(
            index = index,
            oldTopDocOffset = null,
            jumpToTitle = true,
            tryRestorePos = tryRestorePos
        )
    }

    /**
     * ✅ 修复点：向下追加时如果要“保持当前位置”，不要用 scrollbar.value，
     * 记录 viewport 顶部 docOffset 作为锚点，追加+裁剪后再对齐回去。
     */
    private fun appendChapter(index: Int, jumpToTitle: Boolean, keepReadingAnchor: Boolean) {
        if (loadedEnd < 0) return
        if (index != loadedEnd + 1) return

        val oldTop = if (keepReadingAnchor && !jumpToTitle) topVisibleDocOffset() else null

        loadedEnd = index
        syncListSelection(index)

        loadAndAppendIntoEnd(
            index = index,
            oldTopDocOffset = oldTop,
            jumpToTitle = jumpToTitle,
            tryRestorePos = false
        )
    }

    /** 向上拼接：把 index 插到最前面，并保持当前阅读位置不跳 */
    private fun prependChapter(index: Int, jumpToTitle: Boolean) {
        if (loadedStart < 0) return
        if (index != loadedStart - 1) return

        val oldTop = topVisibleDocOffset() ?: 0

        loadedStart = index
        syncListSelection(index)

        loadAndPrependIntoTop(index, oldTopDocOffset = oldTop, jumpToTitle = jumpToTitle)
    }

    // ---------------- load & mutate document ----------------

    private fun loadAndAppendIntoEnd(
        index: Int,
        oldTopDocOffset: Int?,
        jumpToTitle: Boolean,
        tryRestorePos: Boolean
    ) {
        val chapter = chapterModel.get(index) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "加载章节", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val body = Files.readString(chapter.file, Charsets.UTF_8)

                    SwingUtilities.invokeLater {
                        val doc = textArea.document
                        val start = doc.length

                        val chunk = buildString {
                            if (start > 0) append("\n\n")
                            append("【").append(chapter.title).append("】\n\n")
                            append(body)
                        }

                        textArea.append(chunk)

                        val end = doc.length
                        chapterStartOffsets[index] = start
                        chapterEndOffsets[index] = end

                        // ✅ shrink 可能会从顶部裁剪，返回 removedLen
                        val removedLen = shrinkFromTopIfNeeded()

                        // ✅ 追加后的定位策略
                        when {
                            jumpToTitle -> {
                                // 标题 offset 也要考虑被裁剪的长度
                                val titleOffset = (chapterStartOffsets[index]).coerceAtLeast(0)
                                jumpToOffsetStable(titleOffset)
                            }
                            oldTopDocOffset != null -> {
                                // 维持阅读锚点：oldTop 在裁剪后应减去 removedLen
                                val newTop = (oldTopDocOffset - removedLen).coerceAtLeast(0)
                                scrollTopToDocOffsetStable(newTop)
                            }
                        }

                        if (tryRestorePos) restoreReadingPosIfMatches(index)

                        resetAutoCounters()
                        scheduleSaveReadingPos()
                    }
                } catch (e: Exception) {
                    setUiText("读取章节失败：${e.message}")
                }
            }
        })
    }

    private fun loadAndPrependIntoTop(
        index: Int,
        oldTopDocOffset: Int,
        jumpToTitle: Boolean
    ) {
        val chapter = chapterModel.get(index) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "加载章节", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val body = Files.readString(chapter.file, Charsets.UTF_8)

                    SwingUtilities.invokeLater {
                        val doc: Document = textArea.document

                        val chunk = buildString {
                            append("【").append(chapter.title).append("】\n\n")
                            append(body)
                            append("\n\n")
                        }

                        val insertLen = chunk.length

                        try {
                            doc.insertString(0, chunk, null)
                        } catch (_: Exception) {
                            return@invokeLater
                        }

                        for (i in (index + 1)..loadedEnd) {
                            if (chapterStartOffsets[i] >= 0) chapterStartOffsets[i] += insertLen
                            if (chapterEndOffsets[i] >= 0) chapterEndOffsets[i] += insertLen
                        }

                        chapterStartOffsets[index] = 0
                        chapterEndOffsets[index] = insertLen

                        if (!jumpToTitle) {
                            scrollTopToDocOffsetStable(oldTopDocOffset + insertLen)
                        } else {
                            jumpToOffsetStable(0)
                        }

                        shrinkFromBottomIfNeeded()

                        resetAutoCounters()
                        scheduleSaveReadingPos()
                    }
                } catch (e: Exception) {
                    setUiText("读取章节失败：${e.message}")
                }
            }
        })
    }

    // ---------------- stable scrolling helpers ----------------

    private fun jumpToOffsetStable(offset: Int) {
        val safe = offset.coerceIn(0, textArea.document.length)
        SwingUtilities.invokeLater {
            SwingUtilities.invokeLater {
                jumpToOffsetOnce(safe)
            }
        }
    }

    private fun jumpToOffsetOnce(offset: Int) {
        val safe = offset.coerceIn(0, textArea.document.length)
        textArea.caretPosition = safe
        try {
            val r = textArea.modelToView2D(safe)
            textArea.scrollRectToVisible(r.bounds)
        } catch (_: Exception) {}
    }

    private fun scrollTopToDocOffsetStable(docOffset: Int) {
        val safe = docOffset.coerceIn(0, textArea.document.length)
        SwingUtilities.invokeLater {
            SwingUtilities.invokeLater {
                try {
                    val r = textArea.modelToView2D(safe)
                    val y = r.bounds.y
                    val vp = textScroll.viewport
                    vp.viewPosition = Point(vp.viewPosition.x, y)
                } catch (_: Exception) {
                    jumpToOffsetOnce(safe)
                }
            }
        }
    }

    // ---------------- sliding window shrink (both directions) ----------------

    /**
     * ✅ 修复点：从顶部裁剪时，返回本次裁剪掉的字符长度 removedLen，
     * 让“保持阅读锚点”能把 oldTopDocOffset 同步减去 removedLen。
     */
    private fun shrinkFromTopIfNeeded(): Int {
        if (loadedStart < 0 || loadedEnd < 0) return 0
        var totalRemoved = 0

        while ((loadedEnd - loadedStart + 1) > MAX_KEEP_CHAPTERS) {
            val removeChapter = loadedStart
            val end = chapterEndOffsets.getOrNull(removeChapter) ?: -1
            if (end <= 0) {
                loadedStart++
                continue
            }

            val removeLen = end
            try {
                textArea.document.remove(0, removeLen)
            } catch (_: BadLocationException) {
                return totalRemoved
            } catch (_: Exception) {
                return totalRemoved
            }

            totalRemoved += removeLen

            chapterStartOffsets[removeChapter] = -1
            chapterEndOffsets[removeChapter] = -1

            for (i in (removeChapter + 1)..loadedEnd) {
                if (chapterStartOffsets[i] >= 0) chapterStartOffsets[i] -= removeLen
                if (chapterEndOffsets[i] >= 0) chapterEndOffsets[i] -= removeLen
            }

            val newStart = removeChapter + 1
            if (newStart <= loadedEnd && chapterStartOffsets[newStart] >= 0) chapterStartOffsets[newStart] = 0

            loadedStart++
        }

        return totalRemoved
    }

    private fun shrinkFromBottomIfNeeded() {
        if (loadedStart < 0 || loadedEnd < 0) return
        while ((loadedEnd - loadedStart + 1) > MAX_KEEP_CHAPTERS) {
            val removeChapter = loadedEnd
            val start = chapterStartOffsets.getOrNull(removeChapter) ?: -1
            if (start < 0) {
                loadedEnd--
                continue
            }

            val docLen = textArea.document.length
            val removeLen = docLen - start
            if (removeLen <= 0) {
                loadedEnd--
                continue
            }

            try {
                textArea.document.remove(start, removeLen)
            } catch (_: BadLocationException) {
                return
            } catch (_: Exception) {
                return
            }

            chapterStartOffsets[removeChapter] = -1
            chapterEndOffsets[removeChapter] = -1
            loadedEnd--
        }
    }

    private fun resetAppendStateOnly() {
        loadedStart = -1
        loadedEnd = -1
        resetAutoCounters()
    }

    private fun resetAllState() {
        resetAppendStateOnly()
        for (i in chapterStartOffsets.indices) {
            chapterStartOffsets[i] = -1
            chapterEndOffsets[i] = -1
        }
    }

    // ---------------- reading position persistence ----------------

    private fun installReadingPositionSaver() {
        textScroll.verticalScrollBar.model.addChangeListener {
            scheduleSaveReadingPos()
        }
    }

    private fun scheduleSaveReadingPos() {
        if (saveTimer == null) {
            saveTimer = Timer(600) {
                saveTimer?.stop()
                saveTimer = null
                captureAndSaveReadingPos()
            }.apply {
                isRepeats = false
                start()
            }
        } else {
            saveTimer?.restart()
        }
    }

    private fun captureAndSaveReadingPos() {
        val idx = currentChapterIndexFromView()
        if (idx !in 0 until chapterModel.getSize()) return

        val start = chapterStartOffsets.getOrNull(idx) ?: -1
        if (start < 0) return

        val top = topVisibleDocOffset() ?: return
        val inChapter = (top - start).coerceAtLeast(0)

        val st = NovelReaderState.getInstance().state
        st.lastChapterIndex = idx
        st.lastOffsetInChapter = inChapter

        syncListSelection(idx)
    }

    private fun restoreReadingPosIfMatches(index: Int) {
        val st = NovelReaderState.getInstance().state
        if (st.lastChapterIndex != index) return

        val start = chapterStartOffsets.getOrNull(index) ?: -1
        if (start < 0) return

        val target = (start + st.lastOffsetInChapter).coerceIn(0, textArea.document.length)
        scrollTopToDocOffsetStable(target)
        syncListSelection(index)
    }

    // ---------------- auto load by sustained scroll ----------------

    private fun installSustainedScrollAutoLoad() {
        val wheelListener = MouseWheelListener { e: MouseWheelEvent ->
            val down = e.preciseWheelRotation > 0.0
            val up = e.preciseWheelRotation < 0.0
            SwingUtilities.invokeLater {
                if (down) onDownWheel()
                if (up) onUpWheel()
            }
        }
        textScroll.addMouseWheelListener(wheelListener)
    }

    private fun onDownWheel() {
        if (!isAtBottom()) {
            downCountAtBottom = 0
            return
        }
        if (loadedEnd < 0) return
        if (loadedEnd + 1 >= chapterModel.getSize()) return

        val now = System.currentTimeMillis()
        if (now - lastDownTime > AUTO_WINDOW_MS) downCountAtBottom = 0
        lastDownTime = now
        downCountAtBottom++

        if (downCountAtBottom >= AUTO_WHEEL_COUNT) {
            downCountAtBottom = 0
            val next = loadedEnd + 1
            // ✅ 自动向下：追加并保持阅读锚点（不跳标题）
            appendChapter(next, jumpToTitle = false, keepReadingAnchor = true)
        }
    }

    private fun onUpWheel() {
        if (!isAtTop()) {
            upCountAtTop = 0
            return
        }
        if (loadedStart < 0) return
        if (loadedStart - 1 < 0) return

        val now = System.currentTimeMillis()
        if (now - lastUpTime > AUTO_WINDOW_MS) upCountAtTop = 0
        lastUpTime = now
        upCountAtTop++

        if (upCountAtTop >= AUTO_WHEEL_COUNT) {
            upCountAtTop = 0
            val prev = loadedStart - 1
            prependChapter(prev, jumpToTitle = false)
        }
    }

    private fun isAtBottom(): Boolean {
        val m = textScroll.verticalScrollBar.model
        return (m.value + m.extent) >= (m.maximum - BOTTOM_THRESHOLD)
    }

    private fun isAtTop(): Boolean {
        val v = textScroll.verticalScrollBar.value
        return v <= TOP_THRESHOLD
    }

    private fun resetAutoCounters() {
        downCountAtBottom = 0
        lastDownTime = 0L
        upCountAtTop = 0
        lastUpTime = 0L
    }

    private fun setUiText(text: String) {
        SwingUtilities.invokeLater {
            textArea.text = text
            textArea.caretPosition = 0
        }
    }

    // ---------------- list model ----------------

    private class ChapterListModel : AbstractListModel<String>() {
        private var chapters: List<Chapter> = emptyList()
        fun setChapters(newList: List<Chapter>) {
            chapters = newList
            fireContentsChanged(this, 0, maxOf(0, chapters.size - 1))
        }
        fun get(i: Int): Chapter? = chapters.getOrNull(i)
        override fun getSize(): Int = chapters.size
        override fun getElementAt(index: Int): String = chapters[index].title
    }
}
