package com.febridge.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AgentMapperTest {

    @Test
    void mapperLoadsWithRecordConstructorAndJsonbHandler() throws Exception {
        var configuration = new Configuration();
        String resource = "mapper/agent/AgentMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(
                input,
                configuration,
                resource,
                configuration.getSqlFragments()
            ).parse();
        }
        assertTrue(configuration.hasStatement(AgentMapper.class.getName() + ".insert"));
        assertTrue(configuration.hasStatement(AgentMapper.class.getName() + ".findById"));
    }
}
