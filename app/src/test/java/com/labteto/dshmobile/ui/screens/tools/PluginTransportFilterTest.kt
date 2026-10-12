package com.labteto.dshmobile.ui.screens.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTransportFilterTest {
    @Test fun httpAndStdioAreDerivedFromRealTransport() {
        assertTrue(pluginTransportMatches("streamable-http", PluginTransportFilter.HTTP))
        assertTrue(pluginTransportMatches("SSE", PluginTransportFilter.HTTP))
        assertTrue(pluginTransportMatches("STDIO", PluginTransportFilter.STDIO))
        assertFalse(pluginTransportMatches("stdio", PluginTransportFilter.HTTP))
        assertFalse(pluginTransportMatches("http", PluginTransportFilter.STDIO))
        assertFalse(pluginTransportMatches("websocket", PluginTransportFilter.STDIO))
        assertTrue(pluginTransportMatches("future-protocol", PluginTransportFilter.ALL))
    }
}
