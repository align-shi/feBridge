package com.febridge.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JsonbTypeHandlerTest {

    private final JsonbTypeHandler handler = new JsonbTypeHandler();

    @Test
    void bindsAsPostgresOtherAndReadsNestedJson() throws Exception {
        var json = JsonMapper.builder()
            .build()
            .readTree("{\"model\":\"demo\",\"parameters\":{\"temperature\":0.2}}");
        var statement = mock(PreparedStatement.class);
        handler.setNonNullParameter(statement, 1, json, null);
        verify(statement).setObject(1, json.toString(), Types.OTHER);
        var result = mock(ResultSet.class);
        when(result.getString("model_config")).thenReturn(json.toString());
        assertEquals(json, handler.getNullableResult(result, "model_config"));
    }

    @Test
    void handlesSqlNull() throws Exception {
        var result = mock(ResultSet.class);
        assertNull(handler.getNullableResult(result, "model_config"));
    }
}
