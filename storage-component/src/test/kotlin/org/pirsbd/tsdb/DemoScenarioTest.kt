package org.pirsbd.tsdb

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class DemoScenarioTest {

    @Test
    fun `the demo scenario runs end to end and reports both sources`() {
        val output = StringBuilder()

        runDemo(Files.createTempDirectory("tsdb-demo"), output)

        val text = output.toString()
        for (step in 1..10) {
            assertTrue(text.contains("[$step]"), "step $step missing from the demo output:\n$text")
        }
        assertTrue(text.contains("HEAD"), "a series served from the head must be marked:\n$text")
        assertTrue(text.contains("BLOCK"), "a series served from a block must be marked:\n$text")
        assertTrue(
            text.contains("[HEAD+BLOCK]"),
            "the merge of head and block is the one thing the demo must prove:\n$text",
        )
        assertTrue(text.contains("meta.json"), "the block files must be listed:\n$text")
    }

    @Test
    fun `the demo leaves a readable block behind`() {
        val dir = Files.createTempDirectory("tsdb-demo")

        runDemo(dir, StringBuilder())

        val blocks = Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }
        assertTrue(blocks.isNotEmpty(), "the demo must leave at least one block")
        val meta = Files.readString(blocks.first().resolve("meta.json"))
        assertTrue(meta.contains("\"numSeries\""), "meta.json should be readable: $meta")
    }
}
