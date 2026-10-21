package com.opendroid.ai.core.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * In-process event fan-out for native Android listeners and the assistant runtime.
 *
 * The buffer is bounded: a busy sensor source cannot grow memory without limit.
 * Event IDs are retained in a small LRU window to ignore duplicate callbacks.
 */
@Singleton
class AssistantEventRouter @Inject constructor() {

    private val mutableEvents = MutableSharedFlow<AssistantEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<AssistantEvent> = mutableEvents.asSharedFlow()

    private val seenIds = object : LinkedHashMap<String, Unit>(MAX_DEDUPE_IDS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_DEDUPE_IDS
    }

    /**
     * Returns false for a duplicate or when no subscriber can accept an event immediately.
     * DROP_OLDEST means a busy subscriber never blocks Android service callbacks.
     */
    fun publish(event: AssistantEvent): Boolean {
        synchronized(seenIds) {
            if (seenIds.containsKey(event.eventId)) return false
            seenIds[event.eventId] = Unit
        }
        return mutableEvents.tryEmit(event)
    }

    companion object {
        private const val EVENT_BUFFER_CAPACITY = 128
        private const val MAX_DEDUPE_IDS = 512
    }
}
