package com.ch.novelreader

import com.intellij.util.messages.Topic

interface ReaderSettingsListener {
    fun settingsChanged()
}

object ReaderTopics {
    val SETTINGS_CHANGED: Topic<ReaderSettingsListener> =
        Topic.create("Novel Reader Settings Changed", ReaderSettingsListener::class.java)
}
