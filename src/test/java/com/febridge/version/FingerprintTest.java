package com.febridge.version;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FingerprintTest {

    @Test
    void ignoresObjectKeyOrderButPreservesArrayOrderAndValues() {
        var json = JsonMapper.builder().build();
        assertEquals(
            AgentVersions.fingerprint(json.readTree("{\"a\":1,\"b\":{\"x\":2,\"y\":3}}")),
            AgentVersions.fingerprint(json.readTree("{\"b\":{\"y\":3,\"x\":2},\"a\":1}"))
        );
        assertNotEquals(
            AgentVersions.fingerprint(json.readTree("[1,2]")),
            AgentVersions.fingerprint(json.readTree("[2,1]"))
        );
    }
}
