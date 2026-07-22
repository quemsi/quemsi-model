package com.quemsi.model.flow.db.sqlserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.LogMessage;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.RsHelper;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbDomainType;
import com.quemsi.model.flow.db.sql.DbFullTextCatalog;
import com.quemsi.model.flow.db.sql.DbFullTextIndex;
import com.quemsi.model.flow.db.sql.DbFunction;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbTrigger;
import com.quemsi.model.flow.db.sql.DbView;
import com.quemsi.model.util.CommonHelpers;
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
where SCHEMA_NAME(t.schema_id) in {inValues}
;
			""";

    private static final String SQL_FOR_COLUMNS = """
select 
	SCHEMA_NAME(t.schema_id) as schema_name, t.name as table_name,
	c.name as column_name, c.column_id as ordinal_position,
	c.max_length as character_maximum_length,
	/* Alias UDTs (AdventureWorks Flag/Name) may be invisible without VIEW DEFINITION on the type;
	   fall back to the base system type so columns are still discovered for db_datareader users. */
	coalesce(ut.name, st.name) as column_type, st.name as data_type,
	c.max_length as character_octet_length, c.precision as numeric_precision, c.scale as numeric_scale,
	object_definition(c.default_object_id) as column_default, c.is_nullable, c.is_identity
from sys.columns c
	inner join sys.tables t on c.object_id = t.object_id
	/* Base system type: join system_type_id to types.user_type_id (not system_type_id) to avoid
	   duplicating rows for alias UDTs. system_type_id 240 covers hierarchyid/geometry/geography. */
	inner join sys.types st on (
			c.system_type_id = st.user_type_id
			or (c.system_type_id = 240 and c.user_type_id = st.user_type_id)
		)
	left join sys.types ut on c.user_type_id = ut.user_type_id
where schema_name(t.schema_id) in {inValues} and t.[type] = 'U'
order by t.schema_id, t.name, c.column_id
;
    """;

	private static final String SQL_FOR_CONSTRAINTS = """
select * from (
	SELECT kcu.table_schema, kcu.table_name, kcu.constraint_name, 'p' as con_type, kcu.column_name, kcu.ordinal_position,
		null as REFERENCED_SCHEMA_NAME, null as REFERENCED_TABLE_NAME, null as REFERENCED_COLUMN_NAME
	FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
	WHERE OBJECTPROPERTY(OBJECT_ID(CONSTRAINT_SCHEMA + '.' + QUOTENAME(CONSTRAINT_NAME)), 'IsPrimaryKey') = 1
	UNION
	SELECT  schema_name(t.schema_id) as schema_name, CAST (oParent.name AS VARCHAR(255)) as table_name, 
		CAST (oConstraint.name AS VARCHAR(255)) as CONSTRAINT_NAME, 'f' as con_type, CAST ( oParentCol.name AS VARCHAR(255)) as column_name, FKC.constraint_column_id as ordinal,
		schema_name(tRef.schema_id) as REFERENCED_SCHEMA_NAME, CAST ( oReference.name AS VARCHAR(255)) as REFERENCED_TABLE_NAME, CAST (oReferenceCol.name AS VARCHAR(255)) as REFERENCED_COLUMN_NAME
	FROM sys.foreign_key_columns FKC
	    INNER JOIN sys.sysobjects oConstraint ON FKC.constraint_object_id=oConstraint.id 
	    INNER JOIN sys.sysobjects oParent ON FKC.parent_object_id=oParent.id
	    INNER JOIN sys.all_columns oParentCol ON FKC.parent_object_id=oParentCol.object_id 
	            AND FKC.parent_column_id=oParentCol.column_id
	    INNER JOIN sys.sysobjects oReference ON FKC.referenced_object_id=oReference.id
	    INNER JOIN INFORMATION_SCHEMA.COLUMNS oParentColDtl ON oParentColDtl.TABLE_NAME=oParent.name AND oParentColDtl.COLUMN_NAME=oParentCol.name
	    INNER JOIN sys.all_columns oReferenceCol ON FKC.referenced_object_id=oReferenceCol.object_id 
	            AND FKC.referenced_column_id=oReferenceCol.column_id
	    INNER JOIN sys.tables t ON oParentCol.object_id = t.object_id
	    INNER JOIN sys.tables tRef ON oReferenceCol.object_id = tRef.object_id
	UNION
	SELECT kcu.table_schema, kcu.table_name, kcu.constraint_name, 'u' as con_type, kcu.column_name, kcu.ordinal_position,
		null as REFERENCED_SCHEMA_NAME, null as REFERENCED_TABLE_NAME, null as REFERENCED_COLUMN_NAME
	FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
	INNER JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc ON kcu.constraint_name = tc.constraint_name 
		AND kcu.table_schema = tc.table_schema AND kcu.table_name = tc.table_name
	WHERE tc.constraint_type = 'UNIQUE'
) cons
where cons.table_schema in {inValues}
order by cons.table_schema, cons.table_name, cons.ordinal_position 
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
	 ic.is_included_column,
	 xi.secondary_type_desc as XML_SECONDARY_TYPE,
	 using_ind.name as USING_XML_INDEX_NAME
FROM sys.indexes ind 
INNER JOIN sys.index_columns ic ON  ind.object_id = ic.object_id and ind.index_id = ic.index_id 
INNER JOIN sys.columns col ON ic.object_id = col.object_id and ic.column_id = col.column_id 
INNER JOIN sys.tables t ON ind.object_id = t.object_id 
LEFT JOIN sys.xml_indexes xi ON ind.object_id = xi.object_id AND ind.index_id = xi.index_id
LEFT JOIN sys.indexes using_ind ON xi.object_id = using_ind.object_id AND xi.using_xml_index_id = using_ind.index_id
WHERE ind.is_primary_key = 0 AND t.is_ms_shipped = 0 and schema_name(t.schema_id ) in {inValues}
ORDER BY t.schema_id, t.name, ind.name, ic.is_included_column, ic.key_ordinal
;
            """;
	public static final String SQL_FOR_SEQUENCES = """
select schema_name(s.schema_id) as schema_name, s.name as sequence_name, s.start_value, s.minimum_value as min_value, s.maximum_value as max_value,
	s.increment as increment_by, s.is_cycling as cycle, s.cache_size, s.last_used_value as last_value 
from sys.sequences s
where schema_name(s.schema_id) in {inValues}
;
			""";

	private static final String SQL_FOR_CHECK_CONSTRAINTS = """
select 
	SCHEMA_NAME(t.schema_id) as table_schema,
	t.name as table_name,
	cc.name as constraint_name,
	/* Both need VIEW DEFINITION; coalesce covers older engines / edge cases. */
	coalesce(cc.definition, OBJECT_DEFINITION(cc.object_id)) as condef
from sys.check_constraints cc
inner join sys.tables t on cc.parent_object_id = t.object_id
where SCHEMA_NAME(t.schema_id) in {inValues}
;
			""";

	private static final String SQL_FOR_DEFAULT_CONSTRAINTS = """
select schema_name(t.schema_id) as schema_name, t.name as table_name, ac.name as column_name, 
	dc.name as constraint_name, dc.definition
from sys.default_constraints dc 
	inner join sys.all_columns ac ON ac.default_object_id = dc.object_id
	inner join sys.tables t ON ac.object_id = t.object_id
where schema_name(t.schema_id) in {inValues}
;
			""";

	private static final String SQL_FOR_VIEWS = """
select
	schema_name(v.schema_id) as schema_name,
	v.name as view_name,
	m.definition as definition
from sys.views v
inner join sys.sql_modules m on v.object_id = m.object_id
where schema_name(v.schema_id) in {inValues}
order by schema_name(v.schema_id), v.name
;
			""";

	private static final String SQL_FOR_VIEW_DEPS = """
select
	schema_name(v.schema_id) as view_schema,
	v.name as view_name,
	schema_name(dv.schema_id) as dep_schema,
	dv.name as dep_name
from sys.sql_expression_dependencies d
inner join sys.views v on d.referencing_id = v.object_id
inner join sys.views dv on d.referenced_id = dv.object_id
where schema_name(v.schema_id) in {inValues}
  and d.referenced_id is not null
;
			""";

	/** Alias UDTs (CREATE TYPE ... FROM); excludes table types and CLR. */
	static final String SQL_FOR_ALIAS_TYPES = """
select
	schema_name(t.schema_id) as schema_name,
	t.name as type_name,
	st.name as base_type,
	t.max_length,
	t.precision,
	t.scale,
	t.is_nullable
from sys.types t
inner join sys.types st on t.system_type_id = st.user_type_id and st.is_user_defined = 0
where t.is_user_defined = 1
  and t.is_table_type = 0
  and schema_name(t.schema_id) in {inValues}
order by schema_name(t.schema_id), t.name
;
			""";

	/** T-SQL procedures and functions (excludes CLR when definition is unavailable). */
	static final String SQL_FOR_ROUTINES = """
select
	schema_name(o.schema_id) as schema_name,
	o.name as routine_name,
	case when o.[type] in ('P', 'PC') then 'PROCEDURE' else 'FUNCTION' end as routine_type,
	coalesce(m.definition, object_definition(o.object_id)) as definition
from sys.objects o
left join sys.sql_modules m on o.object_id = m.object_id
where o.[type] in ('P', 'PC', 'FN', 'IF', 'TF', 'FS', 'FT')
  and schema_name(o.schema_id) in {inValues}
order by schema_name(o.schema_id), o.name
;
			""";

	static final String SQL_FOR_TRIGGERS = """
select
	schema_name(t.schema_id) as schema_name,
	t.name as table_name,
	tr.name as trigger_name,
	coalesce(m.definition, object_definition(tr.object_id)) as definition
from sys.triggers tr
inner join sys.tables t on tr.parent_id = t.object_id
left join sys.sql_modules m on tr.object_id = m.object_id
where tr.parent_class = 1
  and schema_name(t.schema_id) in {inValues}
order by schema_name(t.schema_id), t.name, tr.name
;
			""";

	static final String SQL_FOR_FULLTEXT_CATALOGS = """
select c.name as catalog_name, c.is_default
from sys.fulltext_catalogs c
order by c.name
;
			""";

	static final String SQL_FOR_FULLTEXT_INDEXES = """
select
	schema_name(t.schema_id) as schema_name,
	t.name as table_name,
	ui.name as unique_index_name,
	fc.name as catalog_name,
	fi.change_tracking_state_desc as change_tracking,
	case
		when fi.stoplist_id is null then 'OFF'
		when fi.stoplist_id = 0 then 'SYSTEM'
		else sl.name
	end as stoplist_name
from sys.fulltext_indexes fi
inner join sys.tables t on fi.object_id = t.object_id
inner join sys.indexes ui on fi.object_id = ui.object_id and fi.unique_index_id = ui.index_id
inner join sys.fulltext_catalogs fc on fi.fulltext_catalog_id = fc.fulltext_catalog_id
left join sys.fulltext_stoplists sl on fi.stoplist_id = sl.stoplist_id
where schema_name(t.schema_id) in {inValues}
order by schema_name(t.schema_id), t.name
;
			""";

	static final String SQL_FOR_FULLTEXT_INDEX_COLUMNS = """
select
	schema_name(t.schema_id) as schema_name,
	t.name as table_name,
	col.name as column_name,
	typecol.name as type_column_name,
	fic.language_id
from sys.fulltext_index_columns fic
inner join sys.tables t on fic.object_id = t.object_id
inner join sys.columns col on fic.object_id = col.object_id and fic.column_id = col.column_id
left join sys.columns typecol on fic.object_id = typecol.object_id and fic.type_column_id = typecol.column_id
where schema_name(t.schema_id) in {inValues}
order by schema_name(t.schema_id), t.name, fic.column_id
;
			""";

	private static final Pattern CREATE_VIEW_AS = Pattern.compile(
		"(?is)^\\s*create\\s+(?:or\\s+alter\\s+)?view\\s+.+?\\s+as\\s+(.*)$"
	);

	public static final String SQL_FOR_SCHEMA = "select s.name from sys.schemas s where s.name = ?;";

	private String name;
	private String dbName;
	private String url;
	private String username;
	private String password;
	private boolean readOnly;
	private Set<String> schemas;
	private HikariDataSource instance;
	private ReentrantLock globalLock;
	
	@Override
	public DatasourceType type() {
		return DatasourceType.SQLSERVER;
	}

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
			/* Connection pool settings for intermittent flow workloads */
			config.setMaximumPoolSize(20);  /* Reasonable max connections per datasource */
			config.setMinimumIdle(0);      /* Allow pool to shrink to empty when unused */
			config.setIdleTimeout(10000); /* Retire idle connections ASAP (Hikari minimum) */
			config.setMaxLifetime(1200000); /* 20 minutes max lifetime */
			config.setConnectionTimeout(30000); /* 30 seconds connection timeout */
			config.setLeakDetectionThreshold(600000); /* 10 minutes leak detection */
			config.setValidationTimeout(5000); /* 5 seconds validation timeout */
			config.setPoolName("HikariPool-" + (this.name != null ? this.name : "SQLServer")); /* Named pool for monitoring */
			globalLock = new ReentrantLock();
			HikariDataSource ds =new HikariDataSource(config);
			instance = ds;
		}
		return instance;
	}

	@Override
    public DbModel getDbModel(Consumer<LogMessage> progress) {
		long startTime = System.currentTimeMillis();
        DbModel dbModel = new DbModel();
		dbModel.setSchemas(getSchemas());
		dbModel.setSourceType(DatasourceType.SQLSERVER.name());
		reportProgress(progress, LogMessage.info("Loading model for schemas: {}", getSchemas()));
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement tps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_ALIAS_TYPES, schemas.size()));
			PreparedStatement ps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_COLUMNS, schemas.size()));
			PreparedStatement cps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_CONSTRAINTS, schemas.size()));
			PreparedStatement ist = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_INDEXES, schemas.size()));
			PreparedStatement sst = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_SEQUENCES, schemas.size()));
			PreparedStatement ckps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_CHECK_CONSTRAINTS, schemas.size()));
			PreparedStatement dcps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_DEFAULT_CONSTRAINTS, schemas.size()));
			PreparedStatement rps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_ROUTINES, schemas.size()));
			PreparedStatement trps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_TRIGGERS, schemas.size()));
			PreparedStatement ftCatPs = con.prepareStatement(SQL_FOR_FULLTEXT_CATALOGS);
			PreparedStatement ftIdxPs = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_FULLTEXT_INDEXES, schemas.size()));
			PreparedStatement ftColPs = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_FULLTEXT_INDEX_COLUMNS, schemas.size()));
			PreparedStatement vps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_VIEWS, schemas.size()));
			PreparedStatement vdps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_VIEW_DEPS, schemas.size()));
		){
			reportProgress(progress, LogMessage.info("Loading alias data types..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> tps.setString(i, schema)));
			try (ResultSet trs = tps.executeQuery()) {
				RsHelper typeHelper = new RsHelper(trs);
				while (trs.next()) {
					String schemaName = trs.getString("SCHEMA_NAME");
					String typeName = trs.getString("TYPE_NAME");
					String baseType = trs.getString("BASE_TYPE");
					Integer maxLength = typeHelper.getInt("MAX_LENGTH");
					Integer precision = typeHelper.getInt("PRECISION");
					Integer scale = typeHelper.getInt("SCALE");
					boolean nullable = trs.getBoolean("IS_NULLABLE");
					dbModel.getDomainTypes().add(DbDomainType.builder()
						.schema(schemaName)
						.name(typeName)
						.baseType(formatAliasBaseType(baseType, maxLength, precision, scale))
						.notNull(!nullable)
						.build());
				}
			}
			reportProgress(progress, LogMessage.info("Loaded {} alias data types", dbModel.getDomainTypes().size()));

			reportProgress(progress, LogMessage.info("Loading tables and columns..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			try (ResultSet rs = ps.executeQuery()) {
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
					
					DbTable table = dbModel.crateIfAbsent(tableName, schemaName);

					table.addColumn(DbColumn.builder().name(columnName).dataType(dataType).ordinalPosition(ordinalPosition).columnType(columnType).maxLength(maxLength).numPrecision(numPrecision).numScale(numScale).columnDefault(columnDefault).nullable(CommonOps.isTrue(nullable)).identity(CommonOps.isTrue(isIdentity)).build());
				}
			}
			applyAliasTypesToColumns(dbModel);
			reportProgress(progress, LogMessage.info("Loaded {} tables", dbModel.getTables().size()));

			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> dcps.setString(i, schema)));
			try (ResultSet dcrs = dcps.executeQuery()) {
				while(dcrs.next()){
					String schemaName = dcrs.getString("SCHEMA_NAME");
					String tableName = dcrs.getString("TABLE_NAME");
					String columnName = dcrs.getString("COLUMN_NAME");
					String constraintName = dcrs.getString("CONSTRAINT_NAME");
					String definition = dcrs.getString("DEFINITION");
					requireDefinition("default-constraint", schemaName, tableName, constraintName, definition);
					DbTable table = dbModel.findTable(CommonHelpers.qualifiedName(schemaName, tableName)).orElseThrow(Exceptions.server("unknow-table").withExtra("schemaName", schemaName).withExtra("tableName", tableName).supplier());
					DbColumn column = table.findColumn(columnName).orElseThrow(Exceptions.server("unknow-column").withExtra("schemaName", schemaName).withExtra("tableName", tableName).withExtra("columnName", columnName).supplier());
					column.setDefaultConstraintName(constraintName);
					if (column.getColumnDefault() == null || column.getColumnDefault().isBlank()) {
						column.setColumnDefault(definition);
					}
				}
			}

			reportProgress(progress, LogMessage.info("Loading constraints..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> cps.setString(i, schema)));
			ResultSet crs = cps.executeQuery();
			Map<String, ReferenceInfo> referenceInfos = new HashMap<>();
			Map<String, ContraintInfo> contraintInfos = new HashMap<>();
			while(crs.next()){
				String schemaName = crs.getString("TABLE_SCHEMA");
				String tableName = crs.getString("TABLE_NAME");
				String constraintName = crs.getString("CONSTRAINT_NAME");
				String conType = crs.getString("CON_TYPE");
				String columnName = crs.getString("COLUMN_NAME");
				String refSchemaName = crs.getString("REFERENCED_SCHEMA_NAME");	
				String refTableName = crs.getString("REFERENCED_TABLE_NAME");
				String refColumnName = crs.getString("REFERENCED_COLUMN_NAME");
				if("p".equals(conType)){
					DbTable table = dbModel.findTable(CommonHelpers.qualifiedName(schemaName, tableName)).orElseThrow(Exceptions.server("unknow-table").withExtra("schemaName", schemaName).withExtra("tableName", tableName).supplier());
					table.getPkColumnNames().add(columnName);
					table.setPkConstraintName(constraintName);
				} else if("f".equals(conType)){
					ReferenceInfo refInfo = referenceInfos.get(constraintName);
					if(refInfo == null){
						refInfo = ReferenceInfo.builder().constraintName(constraintName).srcSchema(schemaName).srcTableName(tableName).srcColumnName(columnName).refSchema(refSchemaName).refTableName(refTableName).refColumnName(refColumnName).build();
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
						contraintInfo = ContraintInfo.builder().constraintName(constraintName).schema(schemaName).tableName(tableName).columnName(columnName).build();
						contraintInfos.put(constraintName, contraintInfo);
					}else{
						contraintInfo.getColumnNames().add(columnName);
					}
				}
			}
			dbModel.getReferenceInfos().addAll(referenceInfos.values());
			dbModel.getContraintInfos().addAll(contraintInfos.values());
			reportProgress(progress, LogMessage.info("Loading indexes..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ist.setString(i, schema)));
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
				String xmlSecondaryType = irs.getString("XML_SECONDARY_TYPE");
				String usingXmlIndexName = irs.getString("USING_XML_INDEX_NAME");
				String qualifiedTableName = new StringBuilder(schemaName).append(".").append(tableName).toString();
				if(cur == null || !qualifiedTableName.equals(cur.qualifiedTableName()) || !indexName.equals(cur.getIndexName())){
					if(cur != null){
						CommonOps.getOrInit(dbModel.getIndexes(), cur.qualifiedTableName(), () -> new HashMap<>()).put(cur.getIndexName(), cur);
					}
					cur = new IndexInfo(schemaName, tableName, indexName, isUnique, indexType);
					cur.setXmlSecondaryType(xmlSecondaryType);
					cur.setUsingXmlIndexName(usingXmlIndexName);
				}
				if(isIncluded){
					cur.getExtraColumns().add(columnName);
				}else{
					cur.getColumns().add(columnName);
				}
			}
			if(cur != null){
				CommonOps.getOrInit(dbModel.getIndexes(), cur.qualifiedTableName(), HashMap::new).put(cur.getIndexName(), cur);
			}
			reportProgress(progress, LogMessage.info("Loading sequences..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> sst.setString(i, schema)));
			ResultSet srs = sst.executeQuery();
			RsHelper rsHelper = new RsHelper(srs);
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
			reportProgress(progress, LogMessage.info("Loading check constraints..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ckps.setString(i, schema)));
			ResultSet ckrs = ckps.executeQuery();
			while (ckrs.next()) {
				String schemaName = ckrs.getString("TABLE_SCHEMA");
				String tableName = ckrs.getString("TABLE_NAME");
				String constraintName = ckrs.getString("CONSTRAINT_NAME");
				String condef = ckrs.getString("CONDEF");
				requireDefinition("check-constraint", schemaName, tableName, constraintName, condef);
				CheckConstraint checkConstraint = CheckConstraint.builder()
					.schema(schemaName)
					.tableName(tableName)
					.constraintName(constraintName)
					.condef(condef)
					.build();
				dbModel.getCheckConstraints().add(checkConstraint);
			}
			reportProgress(progress, LogMessage.info("Loading procedures and functions..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> rps.setString(i, schema)));
			try (ResultSet rrs = rps.executeQuery()) {
				while (rrs.next()) {
					String schemaName = rrs.getString("SCHEMA_NAME");
					String routineName = rrs.getString("ROUTINE_NAME");
					String routineType = rrs.getString("ROUTINE_TYPE");
					String definition = rrs.getString("DEFINITION");
					requireDefinition(routineType != null ? routineType.toLowerCase() : "routine", schemaName, null, routineName, definition);
					dbModel.getFunctions().add(DbFunction.builder()
						.schema(schemaName)
						.name(routineName)
						.routineType(routineType)
						.definition(definition)
						.build());
				}
			}
			reportProgress(progress, LogMessage.info("Loaded {} routines", dbModel.getFunctions().size()));

			reportProgress(progress, LogMessage.info("Loading triggers..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> trps.setString(i, schema)));
			try (ResultSet trrs = trps.executeQuery()) {
				while (trrs.next()) {
					String schemaName = trrs.getString("SCHEMA_NAME");
					String tableName = trrs.getString("TABLE_NAME");
					String triggerName = trrs.getString("TRIGGER_NAME");
					String definition = trrs.getString("DEFINITION");
					requireDefinition("trigger", schemaName, tableName, triggerName, definition);
					dbModel.getTriggers().add(DbTrigger.builder()
						.schema(schemaName)
						.tableName(tableName)
						.name(triggerName)
						.definition(definition)
						.build());
				}
			}
			reportProgress(progress, LogMessage.info("Loaded {} triggers", dbModel.getTriggers().size()));

			reportProgress(progress, LogMessage.info("Loading full-text catalogs and indexes..."));
			try (ResultSet ftrs = ftCatPs.executeQuery()) {
				while (ftrs.next()) {
					dbModel.getFullTextCatalogs().add(DbFullTextCatalog.builder()
						.name(ftrs.getString("catalog_name"))
						.isDefault(ftrs.getBoolean("is_default"))
						.build());
				}
			}
			Map<String, DbFullTextIndex> ftByTable = new HashMap<>();
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ftIdxPs.setString(i, schema)));
			try (ResultSet ftrs = ftIdxPs.executeQuery()) {
				while (ftrs.next()) {
					String schemaName = ftrs.getString("schema_name");
					String tableName = ftrs.getString("table_name");
					DbFullTextIndex ftIndex = DbFullTextIndex.builder()
						.schemaName(schemaName)
						.tableName(tableName)
						.uniqueIndexName(ftrs.getString("unique_index_name"))
						.catalogName(ftrs.getString("catalog_name"))
						.changeTracking(ftrs.getString("change_tracking"))
						.stoplistName(ftrs.getString("stoplist_name"))
						.build();
					ftByTable.put(ftIndex.qualifiedTableName(), ftIndex);
					dbModel.getFullTextIndexes().add(ftIndex);
				}
			}
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ftColPs.setString(i, schema)));
			try (ResultSet ftrs = ftColPs.executeQuery()) {
				RsHelper ftHelper = new RsHelper(ftrs);
				while (ftrs.next()) {
					String schemaName = ftrs.getString("schema_name");
					String tableName = ftrs.getString("table_name");
					DbFullTextIndex ftIndex = ftByTable.get(CommonHelpers.qualifiedName(schemaName, tableName));
					if (ftIndex == null) {
						continue;
					}
					ftIndex.getColumns().add(DbFullTextIndex.Column.builder()
						.columnName(ftrs.getString("column_name"))
						.typeColumnName(ftrs.getString("type_column_name"))
						.languageId(ftHelper.getInt("language_id"))
						.build());
				}
			}
			reportProgress(progress, LogMessage.info("Loaded {} full-text catalogs, {} full-text indexes",
				dbModel.getFullTextCatalogs().size(), dbModel.getFullTextIndexes().size()));

			reportProgress(progress, LogMessage.info("Loading views..."));
			Map<String, DbView> viewsByName = new HashMap<>();
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> vps.setString(i, schema)));
			ResultSet vrs = vps.executeQuery();
			while (vrs.next()) {
				String schemaName = vrs.getString("SCHEMA_NAME");
				String viewName = vrs.getString("VIEW_NAME");
				String definition = stripCreateViewWrapper(vrs.getString("DEFINITION"));
				requireDefinition("view", schemaName, null, viewName, definition);
				DbView view = DbView.builder()
					.schema(schemaName)
					.name(viewName)
					.definition(definition)
					.dependsOnViews(new HashSet<>())
					.build();
				viewsByName.put(view.qualifiedName(), view);
				dbModel.getViews().add(view);
			}
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> vdps.setString(i, schema)));
			ResultSet vdrs = vdps.executeQuery();
			while (vdrs.next()) {
				String viewSchema = vdrs.getString("VIEW_SCHEMA");
				String viewName = vdrs.getString("VIEW_NAME");
				String depSchema = vdrs.getString("DEP_SCHEMA");
				String depName = vdrs.getString("DEP_NAME");
				DbView view = viewsByName.get(CommonHelpers.qualifiedName(viewSchema, viewName));
				if (view != null) {
					view.getDependsOnViews().add(CommonHelpers.qualifiedName(depSchema, depName));
				}
			}
			reportProgress(progress, LogMessage.info("Loaded {} views", dbModel.getViews().size()));
			reportProgress(progress, LogMessage.info("Building model graph..."));
			dbModel.build();
			reportProgress(progress, LogMessage.info("Database model ready ({} tables, {} views, {} routines, {} triggers, {} types, {} ft indexes) in {} secs",
				dbModel.getTables().size(), dbModel.getViews().size(), dbModel.getFunctions().size(),
				dbModel.getTriggers().size(), dbModel.getDomainTypes().size(), dbModel.getFullTextIndexes().size(),
				Duration.ofMillis(System.currentTimeMillis() - startTime).toString()));
		}catch(BaseRuntimeException e){
			throw e;
		}catch(Exception e){
			throw Exceptions.server("unable-to-build-dbmodel").withCause(e).get();
		}
		return dbModel;
    }

	private void reportProgress(Consumer<LogMessage> progress, LogMessage message) {
		if ("WARN".equals(message.getLevel())) {
			log.warn("{}", message);
		} else {
			log.info("{}", message);
		}
		progress.accept(message);
	}

	/**
	 * SQL Server hides module/constraint text unless the login has VIEW DEFINITION.
	 * Fail the backup rather than shipping an incomplete model that cannot restore correctly.
	 */
	static void requireDefinition(String objectType, String schemaName, String tableName, String objectName, String definition) {
		if (definition != null && !definition.isBlank()) {
			return;
		}
		Exceptions ex = Exceptions.server("view-definition-permission-required")
				.withExtra("requiredPermission", "VIEW DEFINITION")
				.withExtra("objectType", objectType)
				.withExtra("schemaName", schemaName)
				.withExtra("objectName", objectName);
		if (tableName != null) {
			ex.withExtra("tableName", tableName);
		}
		throw ex.get();
	}

	/** Point columns at alias UDTs so CREATE TABLE emits the type name (lengths live on the type). */
	static void applyAliasTypesToColumns(DbModel dbModel) {
		if (dbModel.getDomainTypes() == null || dbModel.getDomainTypes().isEmpty() || dbModel.getTables() == null) {
			return;
		}
		Set<String> typeNames = new HashSet<>();
		Set<String> qualifiedNames = new HashSet<>();
		for (DbDomainType domain : dbModel.getDomainTypes()) {
			typeNames.add(domain.getName());
			qualifiedNames.add(domain.qualifiedName());
		}
		for (DbTable table : dbModel.getTables().values()) {
			for (DbColumn column : table.orderedColumns()) {
				String columnType = column.getColumnType();
				if (columnType == null) {
					continue;
				}
				boolean match = typeNames.contains(columnType)
					|| qualifiedNames.contains(columnType)
					|| qualifiedNames.contains(CommonHelpers.qualifiedName(table.getSchema(), columnType));
				if (!match) {
					continue;
				}
				column.setDataType(columnType.contains(".") ? columnType.substring(columnType.lastIndexOf('.') + 1) : columnType);
				column.setMaxLength(null);
				column.setNumPrecision(null);
				column.setNumScale(null);
			}
		}
	}

	/**
	 * Formats the base type clause for {@code CREATE TYPE ... FROM}, using sys.types max_length
	 * (bytes: nchar/nvarchar are 2 bytes per character).
	 */
	static String formatAliasBaseType(String baseType, Integer maxLength, Integer precision, Integer scale) {
		if (baseType == null) {
			return null;
		}
		String type = baseType.toLowerCase();
		if (Set.of("char", "varchar", "binary", "varbinary").contains(type) && maxLength != null) {
			if (maxLength == -1) {
				return type + "(max)";
			}
			return type + "(" + maxLength + ")";
		}
		if (Set.of("nchar", "nvarchar").contains(type) && maxLength != null) {
			if (maxLength == -1) {
				return type + "(max)";
			}
			return type + "(" + (maxLength / 2) + ")";
		}
		if (Set.of("decimal", "numeric").contains(type) && precision != null) {
			return type + "(" + precision + "," + (scale != null ? scale : 0) + ")";
		}
		return type;
	}

	static String stripCreateViewWrapper(String definition) {
		if (definition == null) {
			return null;
		}
		Matcher matcher = CREATE_VIEW_AS.matcher(definition.trim());
		if (matcher.matches()) {
			return matcher.group(1).trim();
		}
		return definition.trim();
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
