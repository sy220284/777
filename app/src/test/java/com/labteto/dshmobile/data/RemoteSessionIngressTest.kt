package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSessionIngressTest {
    @Test
    fun hostNotificationsBecomeTypedMutations() {
        val mutations = mutableListOf<RemoteSessionMutation>()
        val ingress = RemoteSessionIngress(
            emit = mutations::add,
            logger = {},
        )

        ingress.acceptHostFrame(
            RemoteEventFrame.Emit(
                event = "api-session/status",
                args = listOf(JsonPrimitive("s1"), JsonPrimitive(true)),
            ),
        )
        ingress.acceptHostFrame(
            RemoteEventFrame.Emit(
                event = "api-session/activity",
                args = listOf(JsonPrimitive("s1"), JsonPrimitive(42L)),
            ),
        )
        ingress.acceptHostFrame(
            RemoteEventFrame.Emit(
                event = "commands/change",
            ),
        )

        assertEquals(
            listOf(
                RemoteSessionMutation.RunningChanged("s1", true),
                RemoteSessionMutation.ActivityChanged("s1", 42L),
                RemoteSessionMutation.CommandsChanged,
            ),
            mutations,
        )
    }

    @Test
    fun cancelledWaterfallUsesTheSameIngress() {
        val mutations = mutableListOf<RemoteSessionMutation>()
        val ingress = RemoteSessionIngress(
            emit = mutations::add,
            logger = {},
        )

        ingress.acceptHostFrame(RemoteEventFrame.Cancel(eventId = "event-7"))

        assertEquals(
            listOf(RemoteSessionMutation.WaterfallCancelled("event-7")),
            mutations,
        )
    }
}
