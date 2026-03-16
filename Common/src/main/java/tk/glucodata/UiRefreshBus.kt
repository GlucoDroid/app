package tk.glucodata

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object UiRefreshBus {
    sealed interface Event {
        data object DataChanged : Event
        data object StatusOnly : Event
    }

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events = _events.asSharedFlow()

    @JvmStatic
    fun requestDataRefresh() {
        _events.tryEmit(Event.DataChanged)
    }

    @JvmStatic
    fun requestStatusRefresh() {
        _events.tryEmit(Event.StatusOnly)
    }
}
