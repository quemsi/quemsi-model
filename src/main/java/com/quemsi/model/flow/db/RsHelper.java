package com.quemsi.model.flow.db;

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
    public String getString(String columnName) throws SQLException{
        return rs.getString(columnName);
    }
}