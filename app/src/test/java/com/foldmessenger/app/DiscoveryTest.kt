package com.foldmessenger.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the real sweep against whatever is on this machine's network. It needs a
 * Fold Messenger server running locally, which is exactly the situation it has
 * to cope with on the night.
 */
class DiscoveryTest {

    @Test
    fun findsTheServerOnThisNetwork() {
        val started = System.currentTimeMillis()
        val found = Discovery.findServer()
        val took = System.currentTimeMillis() - started
        println("sweep took ${took}ms and returned $found")
        assertNotNull("no server found on the local subnet", found)
        assertTrue("should be an http base", found!!.startsWith("http://"))
        assertTrue("should be on the server port", found.endsWith(":8080"))
        assertTrue("a sweep must not take longer than a reconnect", took < 30_000)
    }
}
