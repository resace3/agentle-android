package dev.agentle.ai.context

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SafeTextTest {
    @Test
    fun `letters, digits, spaces and the allowed punctuation are kept`() {
        assertThat(SafeText.reduce("Hello, world! It's 5:30 & done.", 200)).isEqualTo("Hello world It's 5.30 & done.")
        assertThat(SafeText.reduce("Run - walk", 200)).isEqualTo("Run - walk")
    }

    @Test
    fun `json, markup, links, escapes and line breaks cannot survive`() {
        assertThat(SafeText.reduce("{\"role\":\"system\",\"x\":[1]}", 200)).isEqualTo("role system x 1")
        assertThat(SafeText.reduce("<script>alert(1)</script>", 200)).isEqualTo("script alert 1 script")
        assertThat(SafeText.reduce("https://evil.example/claim?x=1", 200)).isEqualTo("https evil.example claim x 1")
        assertThat(SafeText.reduce("line one\nline two\r\n\tend", 200)).isEqualTo("line one line two end")
        assertThat(SafeText.reduce("a\\b", 200)).isEqualTo("a b")
        assertThat(SafeText.reduce("[click](javascript:alert)", 200)).isEqualTo("click javascript alert")
    }

    @Test
    fun `invisible, control, private-use and unassigned characters are dropped`() {
        val hidden = "ab" + cp(0x200B) + "cd" + cp(0x202E) + "ef" + cp(0x0000, 0x0007, 0x001B) + "gh"
        assertThat(SafeText.reduce(hidden, 200)).isEqualTo("abcdefgh")
        assertThat(SafeText.reduce(cp(0xE000) + "x" + cp(0xFEFF, 0x0378), 200)).isEqualTo("x")
    }

    @Test
    fun `look-alike forms are folded, symbols become spaces and quotes and dashes are unified`() {
        assertThat(SafeText.reduce(cp(0xFF28, 0xFF49), 200)).isEqualTo("Hi")
        assertThat(SafeText.reduce("Run" + cp(0x1F3C3) + "now", 200)).isEqualTo("Run now")
        val typographic = "it" + cp(0x2019) + "s a" + cp(0x2013) + "b " + cp(0x2212) + "c"
        assertThat(SafeText.reduce(typographic, 200)).isEqualTo("it's a-b -c")
        assertThat(SafeText.reduce("a" + cp(0x00A0) + "b" + cp(0x3000) + "c" + cp(0x2028) + "d", 200)).isEqualTo("a b c d")
    }

    @Test
    fun `a colon becomes a dot only between two digits`() {
        assertThat(SafeText.reduce("at 5:30", 200)).isEqualTo("at 5.30")
        assertThat(SafeText.reduce("note: a:b 5: :5", 200)).isEqualTo("note a b 5 5")
    }

    @Test
    fun `letters of every script survive with their combining marks`() {
        val devanagari = cp(0x0928, 0x092E, 0x0938, 0x094D, 0x0924, 0x0947)
        val thai = cp(0x0E2A, 0x0E27, 0x0E31, 0x0E2A, 0x0E14, 0x0E35)
        val latin = "Zo" + cp(0x00EB) + " " + cp(0x00C5) + "ngstr" + cp(0x00F6) + "m"
        assertThat(SafeText.reduce(devanagari, 200)).isEqualTo(devanagari)
        assertThat(SafeText.reduce(thai, 200)).isEqualTo(thai)
        assertThat(SafeText.reduce(latin, 200)).isEqualTo(latin)
        assertThat(SafeText.reduce("e" + cp(0x0301), 200)).isEqualTo(cp(0x00E9))
    }

    @Test
    fun `at most three combining marks follow a letter and marks never start a word`() {
        assertThat(SafeText.reduce("e" + cp(0x0301).repeat(5), 200)).isEqualTo(cp(0x00E9) + cp(0x0301).repeat(3))
        assertThat(SafeText.reduce(cp(0x0301) + "abc", 200)).isEqualTo("abc")
        assertThat(SafeText.reduce("a " + cp(0x0301) + "b", 200)).isEqualTo("a b")
        assertThat(SafeText.reduce("a" + cp(0x20DD) + "b", 200)).isEqualTo("a b")
    }

    @Test
    fun `length counts code points and the cut never leaves a trailing space`() {
        assertThat(SafeText.reduce(cp(0x20000).repeat(5), 3)).isEqualTo(cp(0x20000).repeat(3))
        assertThat(SafeText.reduce("abc def", 4)).isEqualTo("abc")
        assertThat(SafeText.reduce("a".repeat(300), SafeText.ITEM_MAX)).hasLength(SafeText.ITEM_MAX)
        assertThat(
            SafeText.appLabel("A very long application label that keeps going"),
        ).isEqualTo("A very long application label that keeps")
        assertThat(SafeText.reduce("   ", 10)).isEmpty()
        assertThrows<IllegalArgumentException> { SafeText.reduce("x", 0) }
    }

    @Test
    fun `isSafe accepts only reduced, non-empty text within the limit`() {
        assertThat(SafeText.isSafe("Hello there", 20)).isTrue()
        assertThat(SafeText.isSafe("", 20)).isFalse()
        assertThat(SafeText.isSafe("Hello, there", 20)).isFalse()
        assertThat(SafeText.isSafe("Hello there", 5)).isFalse()
        assertThat(SafeText.isSafe(" padded", 20)).isFalse()
    }

    @Test
    fun `reduction is idempotent and its output holds only safe characters (seeded property)`() {
        val ascii = listOf(
            "a", "Z", "7", " ", "\n", "\t", ".", "-", "&", "'", ":", "\"", "\\", "{", "}", "<", ">", "/", "[", "]", ",", "=", "_",
            "#", "@", "%", "5:3", "ab",
        )
        val unicode = listOf(
            0x00E9, 0x0301, 0x20DD, 0x200B, 0x202E, 0xFEFF, 0x0000, 0x001B, 0x2019, 0x2013, 0x2212, 0xFF21, 0x1F600, 0x0928,
            0x094D, 0x0E31, 0x20000, 0xE000, 0x00A0, 0x3000, 0x2028, 0x0378, 0x00B2, 0x2474,
        ).map { cp(it) }
        val alphabet = ascii + unicode
        val random = SplitMix64(20_261_001L)
        repeat(3_000) {
            val raw = buildString { repeat(random.nextInt(60)) { append(random.pick(alphabet)) } }
            val max = 1 + random.nextInt(40)
            val once = SafeText.reduce(raw, max)
            assertThat(SafeText.reduce(once, max)).isEqualTo(once)
            assertThat(once.codePointCount(0, once.length)).isAtMost(max)
            assertThat(once).isEqualTo(once.trim())
            once.codePoints().forEach { assertThat(allowed(it)).isTrue() }
            assertThat(once).doesNotContain("  ")
            if (once.isNotEmpty()) assertThat(SafeText.isSafe(once, max)).isTrue()
        }
    }

    private fun allowed(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        val mark = type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
        return Character.isLetter(codePoint) || Character.isDigit(codePoint) || mark || codePoint in SAFE_PUNCTUATION
    }

    private companion object {
        val SAFE_PUNCTUATION: Set<Int> = " .-&'".map { it.code }.toSet()
    }
}
