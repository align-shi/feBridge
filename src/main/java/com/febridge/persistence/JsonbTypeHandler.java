package com.febridge.persistence;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly referenced by mapper XML; does not alter other String mappings. */
public class JsonbTypeHandler extends BaseTypeHandler<JsonNode> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public void setNonNullParameter(
        PreparedStatement statement,
        int index,
        JsonNode value,
        JdbcType jdbcType
    ) throws SQLException {
        statement.setObject(index, value.toString(), Types.OTHER);
    }

    @Override
    public JsonNode getNullableResult(ResultSet result, String column) throws SQLException {
        return parse(result.getString(column));
    }

    @Override
    public JsonNode getNullableResult(ResultSet result, int column) throws SQLException {
        return parse(result.getString(column));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement statement, int column) throws SQLException {
        return parse(statement.getString(column));
    }

    private JsonNode parse(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return JSON.readTree(value);
        } catch (RuntimeException exception) {
            throw new SQLException("Invalid JSON returned by database", exception);
        }
    }
}
