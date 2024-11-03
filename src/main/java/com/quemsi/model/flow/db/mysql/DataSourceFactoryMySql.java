package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.Column;
import com.quemsi.model.flow.db.sql.DbModel.Table;
import com.mysql.cj.jdbc.MysqlDataSource;

import lombok.Data;

@Data
public class DataSourceFactoryMySql implements DataSourceFactory {
	private static final String SQL_FOR_KEY_COLUMNS = "SELECT CONSTRAINT_SCHEMA, CONSTRAINT_NAME, TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, REFERENCED_TABLE_SCHEMA, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME \n" +
				"FROM information_schema.KEY_COLUMN_USAGE kcu \n" +
				"WHERE kcu.TABLE_SCHEMA = ? \n" +
				"order by kcu.REFERENCED_COLUMN_NAME asc ;";
	private String name;
	private String dbName;
	private String url;
	private String username;
	private String password;
	private DataSource instance;
	
	@Override
	public synchronized DataSource getDataSource() {
		if(instance == null) {
			MysqlDataSource ds =new MysqlDataSource();
			ds.setUrl(this.url);
			ds.setPassword(password);
			ds.setUser(username);
			instance = ds;
		}
		return instance;
	}

	@Override
	public DbModel getDbModel() {
		DbModel dbModel = new DbModel();
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement ps = con.prepareStatement(SQL_FOR_KEY_COLUMNS);
		){
			ps.setString(1, dbName);
			ResultSet rs = ps.executeQuery();
			while(rs.next()){
				String tableName = rs.getString("TABLE_NAME");
				String columnName = rs.getString("COLUMN_NAME");
				String constName = rs.getString("CONSTRAINT_NAME");
				String refTable = rs.getString("REFERENCED_TABLE_NAME");
				String refColumn = rs.getString("REFERENCED_COLUMN_NAME");
				Table table = dbModel.crateIfAbsent(tableName);
				if(refColumn == null){
					table.addColumn(columnName, null, null, null);
				}else{
					Table rTable = dbModel.getTable(refTable).orElseThrow(Exceptions.server("unknow-table-in-fk")
						.withExtra("tableName", tableName).withExtra("columnName", columnName).withExtra("refTable", refTable).withExtra("refColumn", refColumn).supplier());
					Column rColumn = rTable.getColumn(refColumn).orElseThrow(Exceptions.server("unknow-table-in-fk")
						.withExtra("tableName", tableName).withExtra("columnName", columnName).withExtra("refTable", refTable).withExtra("refColumn", refColumn).supplier());
					table.addColumn(columnName, null, rColumn, constName);
				}
			}
		}catch(Exception e){
			throw Exceptions.server("unable-to-build-dbmodel").withCause(e).get();
		}
		return dbModel;
	}
}
