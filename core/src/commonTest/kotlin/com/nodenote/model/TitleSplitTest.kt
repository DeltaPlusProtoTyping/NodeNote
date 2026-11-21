package com.nodenote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleSplitTest {

    @Test
    fun shortTitlesStayWhole() {
        assertNull(splitLongTitle("Parts take too long"))
        assertNull(splitLongTitle("Legacy software is expensive, slow, and lacks capabilities"))
    }

    @Test
    fun breaksAtTheFirstSentenceEnd() {
        assertEquals(
            TitleSplit("become FL certified or just do it after hours", "already showed derrick I'm good on it but he doesn't like me"),
            splitLongTitle("become FL certified or just do it after hours.. already showed derrick I'm good on it but he doesn't like me"),
        )
        assertEquals(
            TitleSplit("Tool offset entry, approval, and logging app", "Combine with spindle tracker for leads / admin"),
            splitLongTitle("Tool offset entry, approval, and logging app. Combine with spindle tracker for leads / admin"),
        )
        assertEquals(
            TitleSplit("VHX into quality to free up a nice office", "that and a printer into quality to streamline workflows"),
            splitLongTitle("VHX into quality to free up a nice office... that and a printer into quality to streamline workflows"),
        )
    }

    @Test
    fun aQuestionKeepsItsMark() {
        assertEquals(
            TitleSplit("Jeff said \"they're\" getting better", "Who is they? are they overloaded?"),
            splitLongTitle("Jeff said \"they're\" getting better. Who is they? are they overloaded?"),
        )
    }

    @Test
    fun otherBreaksOnlyWhenAsked() {
        val title = "Increase quantity per run of inventoried jobs (pro shot) \"can't do this because of agreed stocking\""
        assertNull(splitLongTitle(title, sentenceOnly = true))
        assertEquals(
            TitleSplit("Increase quantity per run of inventoried jobs", "(pro shot) \"can't do this because of agreed stocking\""),
            splitLongTitle(title),
        )
    }

    @Test
    fun noBreakNoSplit() {
        assertNull(splitLongTitle("Operators making stupid changes and not using scientific method at all on the floor"))
        // A break too early to leave a headline doesn't count.
        assertNull(splitLongTitle("Fix it. Operators making stupid changes and not using scientific method", sentenceOnly = true))
    }
}
