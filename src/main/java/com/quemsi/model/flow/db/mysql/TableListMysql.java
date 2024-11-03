package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import lombok.Getter;

public class TableListMysql {
    @Getter
    private Set<String> tables;

    public static class Factory {
        private String showTablesSql = "show tables;";
        public TableListMysql create(Connection conn) throws SQLException{
            try(Statement statement = conn.createStatement();
            ResultSet rs = statement.executeQuery(showTablesSql);){
                Set<String> tables = new HashSet<>();
                while(rs.next()){
                    tables.add(rs.getString(1));
                }
                TableListMysql tableListMysql = new TableListMysql();
                tableListMysql.tables = tables;
                return tableListMysql;
            }
        }    
    }
}
