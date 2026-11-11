package io.github.alalkipgen.autoflow.ui.handoff

import io.github.alalkipgen.autoflow.core.model.HandoffState
import kotlin.test.Test
import kotlin.test.assertEquals

class HandoffBadgesTest {
    @Test
    fun acceptedShotShowsOneAcceptedBadge() {
        assertEquals(listOf(HandoffState.ACCEPTED), handoffBadges(HandoffState.ACCEPTED, accepted = true))
    }

    @Test
    fun acceptedVideoWithOtherStateAddsAcceptedOnce() {
        assertEquals(
            listOf(HandoffState.SENT_TO_FLOW, HandoffState.ACCEPTED),
            handoffBadges(HandoffState.SENT_TO_FLOW, accepted = true),
        )
        assertEquals(listOf(HandoffState.ACCEPTED), handoffBadges(HandoffState.NONE, accepted = true))
    }

    @Test
    fun notAcceptedShowsOnlyState() {
        assertEquals(emptyList(), handoffBadges(HandoffState.NONE, accepted = false))
        assertEquals(listOf(HandoffState.IMPORTED), handoffBadges(HandoffState.IMPORTED, accepted = false))
    }
}
