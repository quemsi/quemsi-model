package com.quemsi.model.flow.db.sqlserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.RsHelper;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class DatasourceFactorySqlserver implements DataSourceFactory{
	public static final Set<String> RESERVED_KEYS = Set.of("PRIMARY");
	
	protected static final String SQL_FOR_TABLES = """
select SCHEMA_NAME(t.schema_id) as schema_name, t.name as table_name
from sys.tables t
where SCHEMA_NAME(t.schema_id) = ?
;
			""";

    private static final String SQL_FOR_COLUMNS = """
select 
	SCHEMA_NAME(t.schema_id) as schema_name, t.name as table_name,
	c.name as column_name, c.column_id as ordinal_position,
	c.max_length as character_maximum_length, ut.name as column_type, st.name as data_type,
	c.max_length as character_octet_length, c.precision as numeric_precision, c.scale as numeric_scale,
	object_definition(c.default_object_id) as column_default, c.is_nullable, c.is_identity,
	pkinfo.constraint_name as pk_constraint_name, 
	fkinfo.constraint_name as fk_constraint_name, fkinfo.schema_name as REFERENCED_SCHEMA, fkinfo.REFERENCED_TABLE_NAME, fkinfo.REFERENCED_COLUMN_NAME
from sys.columns c
inner JOIN sys.tables t ON c.object_id = t.object_id
inner join sys.types st on c.system_type_id = st.system_type_id
inner join sys.types ut on c.user_type_id = ut.user_type_id 
left join (
	-- pk constraints 
	SELECT  kcu.table_schema, kcu.table_name, kcu.constraint_name, kcu.column_name, kcu.ordinal_position
	FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
		WHERE OBJECTPROPERTY(OBJECT_ID(CONSTRAINT_SCHEMA + '.' + QUOTENAME(CONSTRAINT_NAME)), 'IsPrimaryKey') = 1
) pkinfo on pkinfo.table_schema = SCHEMA_NAME(t.schema_id) and pkinfo.table_name = t.name and pkinfo.column_name = c.name
left join (
	SELECT t.schema_id, schema_name(t.schema_id) as schema_name, CAST (oParent.name AS VARCHAR(255)) as table_name, CAST ( oParentCol.name AS VARCHAR(255)) as column_name,
		CAST (oConstraint.name AS VARCHAR(255)) as CONSTRAINT_NAME,
		CAST ( oReference.name AS VARCHAR(255)) as REFERENCED_TABLE_NAME, CAST (oReferenceCol.name AS VARCHAR(255)) as REFERENCED_COLUMN_NAME 
	FROM sys.foreign_key_columns FKC
	    INNER JOIN sys.sysobjects oConstraint ON FKC.constraint_object_id=oConstraint.id 
	    INNER JOIN sys.sysobjects oParent ON FKC.parent_object_id=oParent.id
	    INNER JOIN sys.all_columns oParentCol ON FKC.parent_object_id=oParentCol.object_id /* ID of the object to which this column belongs.*/
	            AND FKC.parent_column_id=oParentCol.column_id/* ID of the column. Is unique within the object.Column IDs might not be sequential.*/
	    INNER JOIN sys.sysobjects oReference ON FKC.referenced_object_id=oReference.id
	    INNER JOIN INFORMATION_SCHEMA.COLUMNS oParentColDtl ON oParentColDtl.TABLE_NAME=oParent.name AND oParentColDtl.COLUMN_NAME=oParentCol.name
	    INNER JOIN sys.all_columns oReferenceCol ON FKC.referenced_object_id=oReferenceCol.object_id /* ID of the object to which this column belongs.*/
	            AND FKC.referenced_column_id=oReferenceCol.column_id/* ID of the column. Is unique within the object.Column IDs might not be sequential.*/
	    inner JOIN sys.tables t ON oParentCol.object_id = t.object_id
) fkinfo on fkinfo.schema_id  = t.schema_id and fkinfo.table_name = t.name and fkinfo.column_name = c.name
where schema_name(t.schema_id) = ? and c.name <> 'rowguid' and t.[type] = 'U' 	
and st.user_type_id = (select min(itq.user_type_id) from sys.types itq where itq.system_type_id  = st.system_type_id)
order by t.schema_id, t.name, c.column_id
;
    """;

    private static final String SQL_FOR_INDEXES = """
SELECT 
	 schema_name(t.schema_id) as schema_name,
     t.name as TABLE_NAME,
     ind.name AS INDEX_NAME,
     col.name AS COLUMN_NAME,
     ic.index_column_id AS SEQ_IN_INDEX,
     ind.is_unique as IS_UNIQUE,
	 ind.type_desc as INDEX_TYPE,
	 ic.is_included_column
FROM sys.indexes ind 
INNER JOIN sys.index_columns ic ON  ind.object_id = ic.object_id and ind.index_id = ic.index_id 
INNER JOIN sys.columns col ON ic.object_id = col.object_id and ic.column_id = col.column_id 
INNER JOIN sys.tables t ON ind.object_id = t.object_id 
WHERE ind.is_primary_key = 0 AND t.is_ms_shipped = 0 and schema_name(t.schema_id ) = ?
ORDER BY t.schema_id, t.name, ind.name, ic.is_included_column, ic.key_ordinal
;
            """;
	public static final String SQL_FOR_SEQUENCES = """
select schema_name(s.schema_id) as schema_name, s.name as sequence_name, s.start_value, s.minimum_value as min_value, s.maximum_value as max_value,
	s.increment as increment_by, s.is_cycling as cycle, s.cache_size, s.last_used_value as last_value 
from sys.sequences s
where schema_name(s.schema_id) = ?
;
			""";
	public static final String SQL_FOR_SCHEMA = "select s.name from sys.schemas s where s.name = ?;";

	private String name;
	private String dbName;
	private String url;
	private String username;
	private String password;
	private String schema;
	private HikariDataSource instance;
	private ReentrantLock globalLock;
	

	@PostConstruct
	public void afterPropertiesSet(){
		getDataSource();
	}

	@PreDestroy
	public void preDestroy(){
		if(instance != null && !instance.isClosed()){
			instance.close();
		}
	}

    @Override
	public synchronized DataSource getDataSource() {
		if(instance == null) {
			HikariConfig config = new HikariConfig();
			config.setJdbcUrl(this.url);
			config.setPassword(password);
			config.setUsername(username);
			// Connection pool settings to prevent exhaustion
			config.setMaximumPoolSize(20);  // Reasonable max connections per datasource 
			config.setMinimumIdle(2);      // Keep minimum idle connections
			config.setIdleTimeout(300000); // 5 minutes idle timeout
			config.setMaxLifetime(1200000); // 20 minutes max lifetime
			config.setConnectionTimeout(30000); // 30 seconds connection timeout
			config.setLeakDetectionThreshold(30000); // 30 seconds leak detection
			config.setValidationTimeout(5000); // 5 seconds validation timeout
			config.setPoolName("HikariPool-" + (this.name != null ? this.name : "SQLServer")); // Named pool for monitoring
			globalLock = new ReentrantLock();
			HikariDataSource ds =new HikariDataSource(config);
			instance = ds;
		}
		return instance;
	}

	@Override
    public DbModel getDbModel() {
        DbModel dbModel = new DbModel();
		dbModel.setSchema(getSchema());
		dbModel.setSourceType(DatasourceType.SQLSERVER.name());
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement ps = con.prepareStatement(SQL_FOR_COLUMNS);
			PreparedStatement ist = con.prepareStatement(SQL_FOR_INDEXES);
			PreparedStatement sst = con.prepareStatement(SQL_FOR_SEQUENCES);
		){
			ps.setString(1, schema);
			ResultSet rs = ps.executeQuery();
			RsHelper rsHelper = new RsHelper(rs);
			while(rs.next()){
				String schemaName = rs.getString("SCHEMA_NAME");
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
				String isIdentity = rs.getString("IS_IDENTITY");
				String pkConstraintName = rs.getString("PK_CONSTRAINT_NAME");
				String constName = rs.getString("FK_CONSTRAINT_NAME");
				String refSchema = rs.getString("REFERENCED_SCHEMA");
				String refTable = rs.getString("REFERENCED_TABLE_NAME");
				String refColumn = rs.getString("REFERENCED_COLUMN_NAME");
				String columnKey = !StringUtils.isEmptyOrNull(pkConstraintName)?"p":(!StringUtils.isEmptyOrNull(constName)?"f":null);
				DbTable table = dbModel.crateIfAbsent(tableName, schemaName);

				DbColumn column = table.addColumn(columnName, dataType, ordinalPosition, columnType, maxLength, numPrecision, numScale, columnKey, columnDefault, nullable, isIdentity);
				if(refColumn != null){
					dbModel.getReferenceInfos().add(ReferenceInfo.builder().srcSchema(schemaName).srcTableName(tableName).srcColumnName(column.getName()).constraintName(constName).refSchema(refSchema).refTableName(refTable).refColumnName(refColumn).build());
				}else{
					column.setConstraintName(constName);
				}
				if(!StringUtils.isEmptyOrNull(pkConstraintName)){
					table.setPkConstraintName(pkConstraintName);
					table.getPkColumnNames().add(columnName);
				}
			}
			ist.setString(1, schema);
			ResultSet irs = ist.executeQuery();
			IndexInfo cur = null;
			while (irs.next()) {
				String schemaName = irs.getString("SCHEMA_NAME");
				String tableName = irs.getString("TABLE_NAME");
				String indexName = irs.getString("INDEX_NAME");
				String columnName = irs.getString("COLUMN_NAME");
				boolean isUnique = irs.getBoolean("IS_UNIQUE");
				String indexType = irs.getString("INDEX_TYPE");
				boolean isIncluded = irs.getBoolean("IS_INCLUDED_COLUMN");
				String qualifiedTableName = new StringBuilder(schemaName).append(".").append(tableName).toString();
				if(cur == null || !qualifiedTableName.equals(cur.qualifiedTableName()) || !indexName.equals(cur.getIndexName())){
					if(cur != null){
						CommonOps.getOrInit(dbModel.getIndexes(), cur.qualifiedTableName(), () -> new HashMap<>()).put(cur.getIndexName(), cur);
					}
					cur = new IndexInfo(schemaName, tableName, indexName, isUnique, indexType);
				}
				if(isIncluded){
					cur.getExtraColumns().add(columnName);
				}else{
					cur.getColumns().add(columnName);
				}
			}
			if(cur != null){
				CommonOps.getOrInit(dbModel.getIndexes(), cur.getTableName(), HashMap::new).put(cur.getIndexName(), cur);
			}
			sst.setString(1, dbModel.getSchema());
			ResultSet srs = sst.executeQuery();
			rsHelper = new RsHelper(srs);
			while (srs.next()) {
				String schemaName = srs.getString("SCHEMA_NAME");
				String sequenceName = srs.getString("SEQUENCE_NAME");
				Long startValue = rsHelper.getLong("START_VALUE");
				Long minValue = rsHelper.getLong("MIN_VALUE");
				Long maxValue = rsHelper.getLong("MAX_VALUE");
				Long incrementBy = rsHelper.getLong("INCREMENT_BY");
				boolean cycle = srs.getBoolean("CYCLE");
				Long cacheSize = rsHelper.getLong("CACHE_SIZE");
				Long lastValue = rsHelper.getLong("LAST_VALUE");
				DbSequence seq = DbSequence.builder().schema(schemaName).name(sequenceName)
					.startValue(startValue).minValue(minValue).maxValue(maxValue).incrementBy(incrementBy)
					.cycle(cycle).cacheSize(cacheSize).lastValue(lastValue)
					.build()
				;
				dbModel.getSequences().add(seq);
			}
			dbModel.build();
		}catch(Exception e){
			throw Exceptions.server("unable-to-build-dbmodel").withCause(e).get();
		}
		return dbModel;
    }

    @Override
	public DDLService ddlService() throws SQLException {
		return new DDLServiceSqlserver(getDataSource().getConnection());
	}

	@Override
	public DMLService dmlService() throws SQLException {
	  return new DMLServiceSqlserver(getDataSource(), globalLock);
	}
}
