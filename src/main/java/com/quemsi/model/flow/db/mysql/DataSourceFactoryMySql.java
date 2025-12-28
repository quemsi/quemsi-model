package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.RsHelper;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
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
FROM INFORMATION_SCHEMA.`COLUMNS` as cols
where cols.TABLE_SCHEMA = ?
order by cols.TABLE_NAME, cols.ORDINAL_POSITION
;
			""";
	private static final String SQL_FOR_CONSTRAINTS = """
select kcu.table_name, kcu.constraint_name, tc.constraint_type, kcu.column_name, coalesce(kcu.position_in_unique_constraint, kcu.ordinal_position) as ORD,
	kcu.referenced_table_name, kcu.referenced_column_name
from INFORMATION_SCHEMA.`KEY_COLUMN_USAGE` kcu 
inner join INFORMATION_SCHEMA.`TABLE_CONSTRAINTS` tc 
	on kcu.constraint_name = tc.constraint_name 
	and kcu.table_schema = tc.table_schema 
	and kcu.table_name = tc.table_name
where kcu.CONSTRAINT_SCHEMA = ?
order by kcu.table_name, kcu.constraint_name, coalesce(kcu.position_in_unique_constraint, kcu.ordinal_position)
;
			""";
	private static final String SQL_FOR_INDEXES = """
SELECT
    st.INDEX_SCHEMA as schema_name,
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
	private String schema;
	private String url;
	private String username;
	private String password;
	private DataSource instance;

	@Override
	public DatasourceType type() {
		return DatasourceType.MYSQL;
	}

	@Override
	public synchronized DataSource getDataSource() {
		if(instance == null) {
			HikariConfig config = new HikariConfig();
			config.setJdbcUrl(this.url);
			config.setPassword(password);
			config.setUsername(username);
			/* Connection pool settings to prevent exhaustion */
			config.setMaximumPoolSize(20);  /* Reasonable max connections per datasource */
			config.setMinimumIdle(2);      /* Keep minimum idle connections */
			config.setIdleTimeout(300000); /* 5 minutes idle timeout */
			config.setMaxLifetime(1200000); /* 20 minutes max lifetime */
			config.setConnectionTimeout(30000); /* 30 seconds connection timeout */
			config.setLeakDetectionThreshold(30000); /* 30 seconds leak detection */
			config.setValidationTimeout(5000); /* 5 seconds validation timeout */
			config.setPoolName("HikariPool-" + (this.name != null ? this.name : "MySQL")); /* Named pool for monitoring */
			HikariDataSource ds =new HikariDataSource(config);
			instance = ds;
		}
		return instance;
	}

	@Override
	public DDLService ddlService() throws SQLException {
		return new DDLServiceMysql(getDataSource());
	}

	@Override
	public DMLService dmlService() throws SQLException {
		return new DMLServiceMysql(getDataSource());
	}

	@Override
	public DbModel getDbModel() {
		DbModel dbModel = new DbModel();
		dbModel.setSourceType(DatasourceType.MYSQL.name());
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement ps = con.prepareStatement(SQL_FOR_COLUMNS);
			PreparedStatement cps = con.prepareStatement(SQL_FOR_CONSTRAINTS);
			PreparedStatement ist = con.prepareStatement(SQL_FOR_INDEXES); 
		){
			ps.setString(1, dbName);
			ResultSet rs = ps.executeQuery();
			RsHelper rsHelper = new RsHelper(rs);
			while(rs.next()){
				String tableName = rs.getString("TABLE_NAME");
				String columnName = rs.getString("COLUMN_NAME");
				Integer ordinalPosition = rsHelper.getInt("ORDINAL_POSITION");
				Integer maxLength = rsHelper.getInt("CHARACTER_MAXIMUM_LENGTH");
				String columnType = rs.getString("COLUMN_TYPE");
				String dataType = rs.getString("DATA_TYPE");
				Integer numPrecision = rsHelper.getInt("NUMERIC_PRECISION");
				Integer numScale = rsHelper.getInt("NUMERIC_SCALE");
				String columnDefault = rs.getString("COLUMN_DEFAULT");
				String nullable = rs.getString("IS_NULLABLE");
				String isIdentity = "FALSE";
				DbTable table = dbModel.crateIfAbsent(tableName);
				table.addColumn(DbColumn.builder().name(columnName).dataType(dataType).ordinalPosition(ordinalPosition).columnType(columnType).maxLength(maxLength).numPrecision(numPrecision).numScale(numScale).columnDefault(columnDefault).nullable(CommonOps.isTrue(nullable)).identity(CommonOps.isTrue(isIdentity)).build());
			}
			cps.setString(1, dbName);
			ResultSet crs = cps.executeQuery();
			Map<String, ReferenceInfo> referenceInfos = new HashMap<>();
			Map<String, ContraintInfo> contraintInfos = new HashMap<>();
			while(crs.next()){
				String tableName = crs.getString("TABLE_NAME");
				String constraintName = crs.getString("CONSTRAINT_NAME");
				String constraintType = crs.getString("CONSTRAINT_TYPE");
				String columnName = crs.getString("COLUMN_NAME");
				String refTableName = crs.getString("REFERENCED_TABLE_NAME");
				String refColumnName = crs.getString("REFERENCED_COLUMN_NAME");
				
				// Map MySQL constraint types to single-letter codes
				String conType;
				if("PRIMARY KEY".equals(constraintType)){
					conType = "p";
				} else if("FOREIGN KEY".equals(constraintType)){
					conType = "f";
				} else if("UNIQUE".equals(constraintType)){
					conType = "u";
				} else {
					conType = null; // Unknown constraint type, skip
				}
				
				if("p".equals(conType)){	
					DbTable table = dbModel.findTable(tableName).orElseThrow(Exceptions.server("unknow-table").withExtra("tableName", tableName).supplier());
					table.getPkColumnNames().add(columnName);
					table.setPkConstraintName(constraintName);
				} else if("f".equals(conType)){
					ReferenceInfo refInfo = referenceInfos.get(constraintName);
					if(refInfo == null){
						refInfo = ReferenceInfo.builder().constraintName(constraintName).srcTableName(tableName).srcColumnName(columnName).refTableName(refTableName).refColumnName(refColumnName).build();
						referenceInfos.put(constraintName, refInfo);
					}else{
						if(refInfo.getRefColumnNames().contains(refColumnName)){
							continue;
						}
						refInfo.getSrcColumnNames().add(columnName);
						refInfo.getRefColumnNames().add(refColumnName);	
					}
				} else if("u".equals(conType)){
					ContraintInfo contraintInfo = contraintInfos.get(constraintName);
					if(contraintInfo == null){
						contraintInfo = ContraintInfo.builder().constraintName(constraintName).schema(dbName).tableName(tableName).columnName(columnName).build();
						contraintInfos.put(constraintName, contraintInfo);
					}else{
						contraintInfo.getColumnNames().add(columnName);
					}
				}
			}
			dbModel.getReferenceInfos().addAll(referenceInfos.values());
			dbModel.getContraintInfos().addAll(contraintInfos.values());
			ist.setString(1, dbName);
			ResultSet irs = ist.executeQuery();
			IndexInfo cur = null;
			while (irs.next()) {
				String schemaName = irs.getString("SCHEMA_NAME");
				String tableName = irs.getString("TABLE_NAME");
				String indexName = irs.getString("INDEX_NAME");
				String columnName = irs.getString("COLUMN_NAME");
				boolean nonUnique = irs.getBoolean("NON_UNIQUE");
				String indexType = irs.getString("INDEX_TYPE");
				String qualifiedTableName = new StringBuilder(schemaName).append(".").append(tableName).toString();
				if(cur == null || !qualifiedTableName.equals(cur.qualifiedTableName()) || !indexName.equals(cur.getIndexName())){
					if(cur != null){
						CommonOps.getOrInit(dbModel.getIndexes(), cur.getTableName(), () -> new HashMap<>()).put(cur.getIndexName(), cur);
					}
					cur = new IndexInfo(schemaName, tableName, indexName, !nonUnique, indexType);
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
