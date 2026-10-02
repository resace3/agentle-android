package dev.agentle.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ResponseBodiesTest {
    @Test
    fun `json content types are recognised with parameters and suffixes`() {
        assertThat(ResponseBodies.isJsonContentType("application/json; charset=utf-8")).isTrue()
        assertThat(ResponseBodies.isJsonContentType("application/problem+json")).isTrue()
        assertThat(ResponseBodies.isJsonContentType("text/html; charset=UTF-8")).isFalse()
        assertThat(ResponseBodies.isJsonContentType(null)).isFalse()
    }

    @Test
    fun `html error pages do not look like json`() {
        assertThat(ResponseBodies.looksLikeJson("  {\"a\":1}")).isTrue()
        assertThat(ResponseBodies.looksLikeJson("[1]")).isTrue()
        assertThat(ResponseBodies.looksLikeJson("<!DOCTYPE html><title>502</title>")).isFalse()
        assertThat(ResponseBodies.looksLikeJson("")).isFalse()
    }

    @Test
    fun `error snippets are short and redacted`() {
        val body = """{"error":"invalid_grant","refresh_token":"rt-very-secret"}""" + " pad".repeat(500)
        val snippet = ResponseBodies.errorSnippet(body, maxChars = 120)!!
        assertThat(snippet.length).isAtMost(121)
        assertThat(snippet).contains("invalid_grant")
        assertThat(snippet).doesNotContain("rt-very-secret")
    }
}
