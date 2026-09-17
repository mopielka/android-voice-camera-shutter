package dev.opielka.voiceshutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordListTest {

    @Test
    fun `splits trims and lowercases`() {
        assertEquals(
            listOf("smile", "cheese", "take a photo"),
            KeywordList.parse(" Smile , CHEESE ,take a photo "),
        )
    }

    @Test
    fun `collapses repeated whitespace inside a phrase`() {
        assertEquals(listOf("take a photo"), KeywordList.parse("take   a  photo"))
    }

    @Test
    fun `drops empty entries from sloppy input`() {
        assertEquals(listOf("smile", "cheese"), KeywordList.parse("smile,,  ,cheese,"))
    }

    @Test
    fun `removes duplicates that differ only by case or spacing`() {
        assertEquals(listOf("smile"), KeywordList.parse("smile, SMILE ,  smile"))
    }

    @Test
    fun `rejects phrases the english model cannot contain`() {
        assertEquals(listOf("smile"), KeywordList.parse("smile, zrób zdjęcie, 123"))
        assertEquals(listOf("zrób zdjęcie", "123"), KeywordList.rejected("smile, zrób zdjęcie, 123"))
    }

    @Test
    fun `keeps apostrophes because english words need them`() {
        assertEquals(listOf("say cheese", "let's go"), KeywordList.parse("say cheese, let's go"))
    }

    @Test
    fun `truncates at the length limit`() {
        val raw = "smile," + "a".repeat(KeywordList.MAX_LENGTH)

        assertTrue(KeywordList.isTooLong(raw))
        assertEquals(KeywordList.MAX_LENGTH, raw.take(KeywordList.MAX_LENGTH).length)
        assertTrue(KeywordList.parse(raw).contains("smile"))
    }

    @Test
    fun `falls back to the default when nothing usable remains`() {
        assertEquals(listOf("smile"), KeywordList.parseOrDefault(" , 123 , "))
        assertEquals(listOf("smile"), KeywordList.parseOrDefault(""))
    }

    @Test
    fun `a quote cannot escape into the vosk grammar`() {
        assertEquals(emptyList<String>(), KeywordList.parse("""smile" , "unk"""))
    }
}
