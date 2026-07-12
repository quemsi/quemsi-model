package com.quemsi.model.flow.db;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import lombok.AllArgsConstructor;

@AllArgsConstructor
public class RsHelper {
    private ResultSet rs;
    
    public Integer getInt(String columnName) throws SQLException{
        int i = rs.getInt(columnName);
        return rs.wasNull()?null:Integer.valueOf(i);
    }
    public Long getLong(String columnName) throws SQLException{
        long i = rs.getLong(columnName);
        return rs.wasNull()?null:Long.valueOf(i);
    }

    /**
     * Reads Oracle NUMBER columns that may exceed {@link Long} range (e.g. ALL_SEQUENCES.MAX_VALUE).
     */
    public Long getLongClamped(String columnName) throws SQLException {
        BigDecimal value = rs.getBigDecimal(columnName);
        if (value == null || rs.wasNull()) {
            return null;
        }
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }

    public String getString(String columnName) throws SQLException{
        return rs.getString(columnName);
    }
}