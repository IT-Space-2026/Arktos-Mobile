package com.arktosbrowser.mobile.search.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPageMobileSearchTest {

    private val engines = listOf(StartPageSearch(), StartPageMobileSearch())

    @Test
    fun `query url uses the current startpage endpoint`() {
        engines.forEach { engine ->
            assertTrue(engine.queryUrl.startsWith("https://www.startpage.com/sp/search"))
        }
    }

    @Test
    fun `query url does not use the retired do path`() {
        engines.forEach { engine ->
            assertFalse(engine.queryUrl.contains("/do/"))
            assertFalse(engine.queryUrl.contains("mobilesearch"))
        }
    }

    @Test
    fun `query url ends with the query parameter`() {
        engines.forEach { engine ->
            assertTrue(engine.queryUrl.endsWith("query="))
        }
    }
}
