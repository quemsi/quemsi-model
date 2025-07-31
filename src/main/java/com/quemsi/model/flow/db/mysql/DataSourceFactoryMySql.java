package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;

import javax.sql.DataSource;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class DataSourceFactoryMySql implements DataSourceFactory {
	private static final String SQL_FOR_COLUMNS = """
SELECT cols.TABLE_NAME, cols.COLUMN_NAME, cols.ORDINAL_POSITION,
    cols.CHARACTER_MAXIMUM_LENGTH, cols.COLUMN_TYPE, cols.DATA_TYPE, cols.CHARACTER_OCTET_LENGTH, cols.NUMERIC_PRECISION, cols.NUMERIC_SCALE,
    cols.COLUMN_KEY, cols.COLUMN_DEFAULT, cols.IS_NULLABLE
    ,  refs.CONSTRAINT_NAME, refs.REFERENCED_TABLE_SCHEMA, refs.REFERENCED_TABLE_NAME, refs.REFERENCED_COLUMN_NAME
FROM INFORMATION_SCHEMA.`COLUMNS` as cols
  LEFT JOIN INFORMATION_SCHEMA.`KEY_COLUMN_USAGE` AS refs
	ON refs.TABLE_SCHEMA=cols.TABLE_SCHEMA
    AND refs.REFERENCED_TABLE_SCHEMA=cols.TABLE_SCHEMA
    AND refs.TABLE_NAME=cols.TABLE_NAME
    AND refs.COLUMN_NAME=cols.COLUMN_NAME
where cols.TABLE_SCHEMA = ?
order by cols.TABLE_NAME, cols.ORDINAL_POSITION
;
			""";
	private static final String SQL_FOR_INDEXES = """
SELECT
    st.TABLE_NAME,
    st.INDEX_NAME,
    st.COLUMN_NAME,
    st.SEQ_IN_INDEX,
    st.NON_UNIQUE,
    st.INDEX_TYPE
FROM INFORMATION_SCHEMA.STATISTICS st
WHERE TABLE_SCHEMA = ?
order by st.TABLE_NAME, st.INDEX_NAME, st.SEQ_IN_INDEX;
			""";

	private String name;
	private String dbName;
	private String url;
	private String username;
	private String password;
	private DataSource instance;
	@Override
	public synchronized DataSource getDataSource() {
		if(instance == null) {
			HikariConfig config = new HikariConfig();
			config.setJdbcUrl(this.url);
			config.setPassword(password);
			config.setUsername(username);
			HikariDataSource ds =new HikariDataSource(config);
			instance = ds;
		}
		return instance;
	}

	@Override
	public DDLService ddlService() throws SQLException {
		return new DDLServiceMysql(getDataSource().getConnection());
	}

	@Override
	public DDLService ddlService(Connection conn){
		return new DDLServiceMysql(conn);
	}

	@Override
	public DMLService dmlService() throws SQLException {
	  return new DMLServiceMysql(getDataSource().getConnection());
	}

	@Override
	public DMLService dmlService(Connection conn){
	  return new DMLServiceMysql(conn);
	}

	@Override
	public DbModel getDbModel() {
		DbModel dbModel = new DbModel();
		dbModel.setSourceType(DatasourceType.MYSQL.name());
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement ps = con.prepareStatement(SQL_FOR_COLUMNS);
			PreparedStatement ist = con.prepareStatement(SQL_FOR_INDEXES); 
		){
			ps.setString(1, dbName);
			ResultSet rs = ps.executeQuery();
			while(rs.next()){
				String tableName = rs.getString("TABLE_NAME");
				String columnName = rs.getString("COLUMN_NAME");
				Integer ordinalPosition = rs.getInt("ORDINAL_POSITION");
				Integer maxLength = rs.getInt("CHARACTER_MAXIMUM_LENGTH");
				String columnType = rs.getString("COLUMN_TYPE");
				String dataType = rs.getString("DATA_TYPE");
				Integer numPrecision = rs.getInt("NUMERIC_PRECISION");
				Integer numScale = rs.getInt("NUMERIC_SCALE");
				String columnKey = rs.getString("COLUMN_KEY");
				String columnDefault = rs.getString("COLUMN_DEFAULT");
				String nullable = rs.getString("IS_NULLABLE");
				String constName = rs.getString("CONSTRAINT_NAME");
				String refTable = rs.getString("REFERENCED_TABLE_NAME");
				String refColumn = rs.getString("REFERENCED_COLUMN_NAME");
				DbTable table = dbModel.crateIfAbsent(tableName);
				DbColumn column = table.addColumn(columnName, dataType, ordinalPosition, columnType, maxLength, numPrecision, numScale, columnKey, columnDefault, nullable);
				if(refColumn != null){
					dbModel.getReferenceInfos().add(ReferenceInfo.builder().srcTable(tableName).srcColumnName(column.getName()).constraintName(constName).refTableName(refTable).refColumnName(refColumn).build());
				}
				if("PRI".equals(columnKey)){
					table.getPkColumnNames().add(columnName);
				}
			}
			ist.setString(1, dbName);
			ResultSet irs = ist.executeQuery();
			IndexInfo cur = null;
			while (irs.next()) {
				String tableName = irs.getString("TABLE_NAME");
				String indexName = irs.getString("INDEX_NAME");
				String columnName = irs.getString("COLUMN_NAME");
				boolean nonUnique = irs.getBoolean("NON_UNIQUE");
				String indexType = irs.getString("INDEX_TYPE");
				if(cur == null || !tableName.equals(cur.getTableName()) || !indexName.equals(cur.getIndexName())){
					if(cur != null){
						CommonOps.getOrInit(dbModel.getIndexes(), cur.getTableName(), () -> new HashMap<>()).put(cur.getIndexName(), cur);
					}
					cur = new IndexInfo(tableName, indexName, !nonUnique, indexType);
				}
				cur.getColumns().add(columnName);
			}
			if(cur != null){
				CommonOps.getOrInit(dbModel.getIndexes(), cur.getTableName(), HashMap::new).put(cur.getIndexName(), cur);
			}
			dbModel.build();
		}catch(Exception e){
			throw Exceptions.server("unable-to-build-dbmodel").withCause(e).get();
		}
		return dbModel;
	}
}
