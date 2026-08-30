package com.quemsi.model.flow.db.postgres;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.in.CustomSerializedColumn;

/**
 * Binds backup page values for PostgreSQL the same way restore does.
 * Archive JSON stores jsonb/point/etc. as strings; {@link Types#OTHER} lets the
 * server cast instead of treating the parameter as varchar.
 */
public final class PostgresArchiveBinder {
    private PostgresArchiveBinder() {
    }

    public static void bind(PreparedStatement ps, Connection conn, int parameterIndex,
            String tableName, DbColumn column, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(parameterIndex, Types.NULL);
            return;
        }
        if (value instanceof List<?> listVal) {
            Array arrVal = conn.createArrayOf("varchar", listVal.toArray());
            ps.setArray(parameterIndex, arrVal);
            return;
        }
        if (value instanceof Map<?, ?> mapVal) {
            if ("tsvector".equals(mapVal.get("type"))) {
                ps.setString(parameterIndex, (String) mapVal.get("value"));
                return;
            }
            if ("oid".equals(mapVal.get("dbType"))) {
                String encodedData = (String) mapVal.get("data");
                byte[] binaryData = Base64.getDecoder().decode(encodedData);
                ps.setBytes(parameterIndex, binaryData);
                return;
            }
            throw Exceptions.server("column-type-not-supported")
                .withExtra("table", tableName)
                .withExtra("columnIndex", parameterIndex)
                .withExtra("column", column)
                .withExtra("value", mapVal)
                .get();
        }
        if (value instanceof CustomSerializedColumn serializedColumn) {
            ps.setBytes(parameterIndex, serializedColumn.getData());
            return;
        }
        ps.setObject(parameterIndex, value, Types.OTHER);
    }
}
