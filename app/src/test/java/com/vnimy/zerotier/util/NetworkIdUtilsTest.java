package com.vnimy.zerotier.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class NetworkIdUtilsTest {

    @Test
    public void extractsPlainId() {
        assertEquals("8056C2E21C000001", NetworkIdUtils.extractNetworkId("8056c2e21c000001"));
    }

    @Test
    public void extractsIdWithPrefixAndSeparators() {
        assertEquals("8056C2E21C000001", NetworkIdUtils.extractNetworkId("0x8056 c2e2 1c00 0001"));
        assertEquals("8056C2E21C000001", NetworkIdUtils.extractNetworkId("8056-c2e2-1c00-0001"));
    }

    @Test
    public void extractsIdFromUrl() {
        assertEquals("8056C2E21C000001",
                NetworkIdUtils.extractNetworkId("https://my.zerotier.com/network/8056c2e21c000001"));
    }

    @Test
    public void extractsIdFromUri() {
        assertEquals("8056C2E21C000001",
                NetworkIdUtils.extractNetworkId("zerotier://8056c2e21c000001"));
    }

    @Test
    public void extractsIdFromJson() {
        assertEquals("8056C2E21C000001",
                NetworkIdUtils.extractNetworkId("{\"networkId\":\"8056c2e21c000001\"}"));
    }

    @Test
    public void rejectsInvalidContent() {
        assertNull(NetworkIdUtils.extractNetworkId(null));
        assertNull(NetworkIdUtils.extractNetworkId(""));
        assertNull(NetworkIdUtils.extractNetworkId("8056c2e21c00000"));
        assertNull(NetworkIdUtils.extractNetworkId("8056c2e21c0000012"));
        assertNull(NetworkIdUtils.extractNetworkId("zzzzzzzzzzzzzzzz"));
        assertNull(NetworkIdUtils.extractNetworkId("8056c2e21c0000018056c2e21c000001"));
    }
}
