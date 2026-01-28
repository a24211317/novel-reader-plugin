package com.ch.novelreader

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(
    name = "NovelReaderState",
    storages = [Storage("NovelReader.xml")]
)
class NovelReaderState : PersistentStateComponent<NovelReaderState.State> {

    data class State(
        var novelFilePath: String = "",
        var fontSize: Int = 16,
        var lastChapterIndex: Int = 0,
        var lastOffsetInChapter: Int = 0
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun getInstance(): NovelReaderState =
            ApplicationManager.getApplication().getService(NovelReaderState::class.java)
    }
}
