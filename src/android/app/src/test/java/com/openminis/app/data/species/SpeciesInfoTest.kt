package com.openminis.app.data.species

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SpeciesInfoTest {
    private val wiki = File.createTempFile("species_wiki", ".tsv").apply {
        deleteOnExit()
        writeText(
            """
            Pycnonotus	鹎属	鹎属是鹎科的一属。
            Pycnonotus sinensis	白頭鵯	白头鹎是鹎属小型鸣禽。
            Passer montanus	麻雀	麻雀是麻雀属的鸟。
            broken line without tabs
            """.trimIndent(),
        )
    }

    @Test fun ownArticleWinsOverTheGenusListedBeforeIt() {
        assertEquals(Triple("Pycnonotus sinensis", "白頭鵯", "白头鹎是鹎属小型鸣禽。"), SpeciesInfo.article(wiki, "Pycnonotus sinensis", "Pycnonotus"))
    }

    @Test fun noOwnArticleFallsBackToTheGenus() {
        assertEquals("Pycnonotus", SpeciesInfo.article(wiki, "Pycnonotus jocosus", "Pycnonotus")?.first)
    }

    @Test fun aGenusIsLookedUpByItself() {
        assertEquals("鹎属", SpeciesInfo.article(wiki, "Pycnonotus", null)?.second)
    }

    @Test fun nothingWhenNeitherIsThere() {
        assertNull(SpeciesInfo.article(wiki, "Felis catus", "Felis"))
        assertNull(SpeciesInfo.article(wiki, "broken line without tabs", null))
    }
}
