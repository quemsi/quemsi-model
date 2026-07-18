package com.quemsi.model.flow.db.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.RsHelper;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbFunction;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbView;
import com.quemsi.model.util.CommonHelpers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class DatasourceFactoryOracle implements DataSourceFactory {
	public static final Set<String> RESERVED_KEYS = Set.of(
		"ACCESS", "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "AUDIT", "BETWEEN", "BY", "CHAR", "CHECK", "CLUSTER",
		"COLUMN", "COMMENT", "COMPRESS", "CONNECT", "CREATE", "CURRENT", "DATE", "DECIMAL", "DEFAULT", "DELETE", "DESC",
		"DISTINCT", "DROP", "ELSE", "EXCLUSIVE", "EXISTS", "FILE", "FLOAT", "FOR", "FROM", "GRANT", "GROUP", "HAVING",
		"IDENTIFIED", "IMMEDIATE", "IN", "INCREMENT", "INDEX", "INITIAL", "INSERT", "INTEGER", "INTERSECT", "INTO", "IS",
		"LEVEL", "LIKE", "LOCK", "LONG", "MAXEXTENTS", "MINUS", "MODE", "MODIFY", "NOAUDIT", "NOCOMPRESS", "NOT", "NOWAIT",
		"NULL", "NUMBER", "OF", "OFFLINE", "ON", "ONLINE", "OPTION", "OR", "ORDER", "PCTFREE", "PRIOR", "PUBLIC", "RAW",
		"RENAME", "RESOURCE", "REVOKE", "ROW", "ROWID", "ROWNUM", "ROWS", "SELECT", "SESSION", "SET", "SHARE", "SIZE",
		"SMALLINT", "START", "SUCCESSFUL", "SYNONYM", "SYSDATE", "TABLE", "THEN", "TO", "TRIGGER", "UID", "UNION", "UNIQUE",
		"UPDATE", "USER", "VALIDATE", "VALUES", "VARCHAR", "VARCHAR2", "VIEW", "WHENEVER", "WHERE", "WITH"
	);

	protected static final String SQL_FOR_TABLES = """
select t.OWNER as schema_name, t.TABLE_NAME as table_name
from ALL_TABLES t
where t.OWNER in {inValues}
  and t.TABLE_NAME not like 'BIN$%%'
  and t.DROPPED = 'NO'
  and nvl(t.SECONDARY, 'N') = 'N'
  and nvl(t.NESTED, 'NO') = 'NO'
  and not exists (
	select 1 from ALL_MVIEWS mv
	where mv.OWNER = t.OWNER and mv.MVIEW_NAME = t.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEW_LOGS ml
	where ml.LOG_OWNER = t.OWNER and ml.LOG_TABLE = t.TABLE_NAME
  )
;
			""";

	private static final String SQL_FOR_COLUMNS = """
select
	c.OWNER as schema_name, c.TABLE_NAME as table_name,
	c.COLUMN_NAME as column_name, c.COLUMN_ID as ordinal_position,
	case when c.DATA_TYPE = 'NUMBER' then null else c.CHAR_LENGTH end as character_maximum_length,
	c.DATA_TYPE as column_type, c.DATA_TYPE as data_type,
	c.DATA_LENGTH as character_octet_length, c.DATA_PRECISION as numeric_precision, c.DATA_SCALE as numeric_scale,
	c.DATA_DEFAULT as column_default, c.NULLABLE as is_nullable
from ALL_TAB_COLUMNS c
where c.OWNER in {inValues}
  and c.COLUMN_NAME not like 'SYS_NC%%'
  and exists (
	select 1 from ALL_TABLES t
	where t.OWNER = c.OWNER and t.TABLE_NAME = c.TABLE_NAME
	  and t.DROPPED = 'NO'
	  and nvl(t.SECONDARY, 'N') = 'N'
	  and nvl(t.NESTED, 'NO') = 'NO'
  )
  and not exists (
	select 1 from ALL_VIEWS v
	where v.OWNER = c.OWNER and v.VIEW_NAME = c.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEWS mv
	where mv.OWNER = c.OWNER and mv.MVIEW_NAME = c.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEW_LOGS ml
	where ml.LOG_OWNER = c.OWNER and ml.LOG_TABLE = c.TABLE_NAME
  )
order by c.OWNER, c.TABLE_NAME, c.COLUMN_ID
;
			""";

	private static final String SQL_FOR_CONSTRAINTS = """
select
	ac.OWNER as table_schema, ac.TABLE_NAME as table_name, ac.CONSTRAINT_NAME,
	case ac.CONSTRAINT_TYPE when 'P' then 'p' when 'R' then 'f' when 'U' then 'u' end as con_type,
	acc.COLUMN_NAME as column_name, acc.POSITION as ordinal_position,
	r_ac.OWNER as referenced_schema_name, r_ac.TABLE_NAME as referenced_table_name,
	r_acc.COLUMN_NAME as referenced_column_name
from ALL_CONSTRAINTS ac
join ALL_CONS_COLUMNS acc on ac.OWNER = acc.OWNER and ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME and ac.TABLE_NAME = acc.TABLE_NAME
left join ALL_CONSTRAINTS r_ac on ac.R_OWNER = r_ac.OWNER and ac.R_CONSTRAINT_NAME = r_ac.CONSTRAINT_NAME
left join ALL_CONS_COLUMNS r_acc on r_ac.OWNER = r_acc.OWNER and r_ac.CONSTRAINT_NAME = r_acc.CONSTRAINT_NAME and r_acc.POSITION = acc.POSITION
where ac.OWNER in {inValues}
  and ac.CONSTRAINT_TYPE in ('P', 'R', 'U')
  and exists (
	select 1 from ALL_TABLES t
	where t.OWNER = ac.OWNER and t.TABLE_NAME = ac.TABLE_NAME
	  and t.DROPPED = 'NO'
	  and nvl(t.SECONDARY, 'N') = 'N'
	  and nvl(t.NESTED, 'NO') = 'NO'
  )
  and not exists (
	select 1 from ALL_VIEWS v
	where v.OWNER = ac.OWNER and v.VIEW_NAME = ac.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEWS mv
	where mv.OWNER = ac.OWNER and mv.MVIEW_NAME = ac.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEW_LOGS ml
	where ml.LOG_OWNER = ac.OWNER and ml.LOG_TABLE = ac.TABLE_NAME
  )
order by ac.OWNER, ac.TABLE_NAME, ac.CONSTRAINT_NAME, acc.POSITION
;
			""";

	public static final String SQL_FOR_CONSTRAINT_NAMES = """
select ac.OWNER as schema_name, ac.CONSTRAINT_NAME as constraint_name
from ALL_CONSTRAINTS ac
where ac.OWNER in {inValues}
;
			""";

	private static final String SQL_FOR_INDEXES = """
select
	ai.TABLE_OWNER as schema_name, ai.TABLE_NAME as table_name, ai.INDEX_NAME,
	aic.COLUMN_NAME, aic.COLUMN_POSITION as seq_in_index,
	case when ai.UNIQUENESS = 'UNIQUE' then 1 else 0 end as is_unique,
	ai.INDEX_TYPE as index_type,
	0 as is_included_column
from ALL_INDEXES ai
join ALL_IND_COLUMNS aic on ai.OWNER = aic.INDEX_OWNER and ai.INDEX_NAME = aic.INDEX_NAME
	and ai.TABLE_OWNER = aic.TABLE_OWNER and ai.TABLE_NAME = aic.TABLE_NAME
where ai.TABLE_OWNER in {inValues}
  and ai.INDEX_TYPE not in ('LOB', 'DOMAIN')
  and ai.INDEX_NAME not like 'I_SNAP$%%'
  and not exists (
	select 1 from ALL_IND_COLUMNS bad
	where bad.INDEX_OWNER = ai.OWNER and bad.INDEX_NAME = ai.INDEX_NAME
	  and bad.COLUMN_NAME like 'SYS_NC%%'
  )
  and exists (
	select 1 from ALL_TABLES t
	where t.OWNER = ai.TABLE_OWNER and t.TABLE_NAME = ai.TABLE_NAME
	  and t.DROPPED = 'NO'
	  and nvl(t.SECONDARY, 'N') = 'N'
	  and nvl(t.NESTED, 'NO') = 'NO'
  )
  and not exists (
	select 1 from ALL_VIEWS v
	where v.OWNER = ai.TABLE_OWNER and v.VIEW_NAME = ai.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEWS mv
	where mv.OWNER = ai.TABLE_OWNER and mv.MVIEW_NAME = ai.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEW_LOGS ml
	where ml.LOG_OWNER = ai.TABLE_OWNER and ml.LOG_TABLE = ai.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_CONSTRAINTS ac
	where ac.OWNER = ai.OWNER and ac.INDEX_NAME = ai.INDEX_NAME and ac.CONSTRAINT_TYPE in ('P', 'U')
  )
order by ai.TABLE_OWNER, ai.TABLE_NAME, ai.INDEX_NAME, aic.COLUMN_POSITION
;
			""";

	public static final String SQL_FOR_SEQUENCES = """
select s.SEQUENCE_OWNER as schema_name, s.SEQUENCE_NAME as sequence_name,
	s.MIN_VALUE as min_value, s.MAX_VALUE as max_value, s.INCREMENT_BY as increment_by,
	case when s.CYCLE_FLAG = 'Y' then 1 else 0 end as cycle,
	s.CACHE_SIZE as cache_size, s.LAST_NUMBER as last_value, s.MIN_VALUE as start_value
from ALL_SEQUENCES s
where s.SEQUENCE_OWNER in {inValues}
;
			""";

	private static final String SQL_FOR_CHECK_CONSTRAINTS = """
select
	ac.OWNER as table_schema, ac.TABLE_NAME as table_name, ac.CONSTRAINT_NAME,
	ac.SEARCH_CONDITION_VC as condef
from ALL_CONSTRAINTS ac
where ac.OWNER in {inValues}
  and ac.CONSTRAINT_TYPE = 'C'
  and ac.GENERATED = 'USER NAME'
  and ac.SEARCH_CONDITION_VC is not null
  and exists (
	select 1 from ALL_TABLES t
	where t.OWNER = ac.OWNER and t.TABLE_NAME = ac.TABLE_NAME
	  and t.DROPPED = 'NO'
	  and nvl(t.SECONDARY, 'N') = 'N'
	  and nvl(t.NESTED, 'NO') = 'NO'
  )
  and not exists (
	select 1 from ALL_VIEWS v
	where v.OWNER = ac.OWNER and v.VIEW_NAME = ac.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEWS mv
	where mv.OWNER = ac.OWNER and mv.MVIEW_NAME = ac.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEW_LOGS ml
	where ml.LOG_OWNER = ac.OWNER and ml.LOG_TABLE = ac.TABLE_NAME
  )
order by ac.OWNER, ac.TABLE_NAME, ac.CONSTRAINT_NAME
;
			""";

	private static final String SQL_FOR_VIEWS = """
select
	v.OWNER as schema_name,
	v.VIEW_NAME as view_name,
	v.TEXT as definition
from ALL_VIEWS v
where v.OWNER in {inValues}
order by v.OWNER, v.VIEW_NAME
;
			""";

	private static final String SQL_FOR_VIEW_DEPS = """
select
	d.OWNER as view_schema,
	d.NAME as view_name,
	d.REFERENCED_OWNER as dep_schema,
	d.REFERENCED_NAME as dep_name
from ALL_DEPENDENCIES d
where d.TYPE = 'VIEW'
  and d.REFERENCED_TYPE = 'VIEW'
  and d.OWNER in {inValues}
;
			""";

	private static final String SQL_FOR_ROUTINES = """
select
	o.OWNER as schema_name,
	o.OBJECT_NAME as routine_name,
	o.OBJECT_TYPE as routine_type
from ALL_OBJECTS o
where o.OWNER in {inValues}
  and o.OBJECT_TYPE in ('FUNCTION', 'PROCEDURE')
order by o.OWNER, o.OBJECT_TYPE, o.OBJECT_NAME
;
			""";

	private static final String SQL_FOR_ROUTINE_DDL = """
select DBMS_METADATA.GET_DDL(?, ?, ?) as definition from DUAL
;
			""";

	private static final String SQL_FOR_ROUTINE_SOURCE = """
select s.TEXT as text
from ALL_SOURCE s
where s.OWNER = ?
  and s.NAME = ?
  and s.TYPE = ?
order by s.LINE
;
			""";

	private static final String SQL_FOR_COLUMN_COMMENTS = """
select
	cc.OWNER as schema_name, cc.TABLE_NAME as table_name, cc.COLUMN_NAME as column_name, cc.COMMENTS as comments
from ALL_COL_COMMENTS cc
where cc.OWNER in {inValues}
  and cc.COMMENTS is not null
  and exists (
	select 1 from ALL_TABLES t
	where t.OWNER = cc.OWNER and t.TABLE_NAME = cc.TABLE_NAME
	  and t.DROPPED = 'NO'
	  and nvl(t.SECONDARY, 'N') = 'N'
	  and nvl(t.NESTED, 'NO') = 'NO'
  )
  and not exists (
	select 1 from ALL_VIEWS v
	where v.OWNER = cc.OWNER and v.VIEW_NAME = cc.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEWS mv
	where mv.OWNER = cc.OWNER and mv.MVIEW_NAME = cc.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEW_LOGS ml
	where ml.LOG_OWNER = cc.OWNER and ml.LOG_TABLE = cc.TABLE_NAME
  )
;
			""";

	private static final String SQL_FOR_TABLE_COMMENTS = """
select
	tc.OWNER as schema_name, tc.TABLE_NAME as table_name, tc.COMMENTS as comments
from ALL_TAB_COMMENTS tc
where tc.OWNER in {inValues}
  and tc.TABLE_TYPE = 'TABLE'
  and tc.COMMENTS is not null
  and exists (
	select 1 from ALL_TABLES t
	where t.OWNER = tc.OWNER and t.TABLE_NAME = tc.TABLE_NAME
	  and t.DROPPED = 'NO'
	  and nvl(t.SECONDARY, 'N') = 'N'
	  and nvl(t.NESTED, 'NO') = 'NO'
  )
  and not exists (
	select 1 from ALL_MVIEWS mv
	where mv.OWNER = tc.OWNER and mv.MVIEW_NAME = tc.TABLE_NAME
  )
  and not exists (
	select 1 from ALL_MVIEW_LOGS ml
	where ml.LOG_OWNER = tc.OWNER and ml.LOG_TABLE = tc.TABLE_NAME
  )
;
			""";

	public static final String SQL_FOR_SCHEMA = "select 1 from ALL_USERS where USERNAME = ?";

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
		return DatasourceType.ORACLE;
	}

	@Override
	public String connectionHealthCheckQuery() {
		return "SELECT 1 FROM DUAL";
	}

	@PostConstruct
	public void afterPropertiesSet() {
		getDataSource();
	}

	@PreDestroy
	public void preDestroy() {
		if (instance != null && !instance.isClosed()) {
			instance.close();
		}
	}

	@Override
	public synchronized DataSource getDataSource() {
		if (instance == null) {
			HikariConfig config = new HikariConfig();
			config.setJdbcUrl(this.url);
			config.setPassword(password);
			config.setUsername(username);
			config.setMaximumPoolSize(20);
			config.setMinimumIdle(0);
			config.setIdleTimeout(10000);
			config.setMaxLifetime(1200000);
			config.setConnectionTimeout(30000);
			config.setLeakDetectionThreshold(600000);
			config.setValidationTimeout(5000);
			config.setPoolName("HikariPool-" + (this.name != null ? this.name : "Oracle"));
			globalLock = new ReentrantLock();
			instance = new HikariDataSource(config);
		}
		return instance;
	}

	@Override
	public DbModel getDbModel() {
		DbModel dbModel = new DbModel();
		try (Connection con = getDataSource().getConnection()) {
			Set<String> effectiveSchemas = resolveSchemas(con);
			dbModel.setSchemas(effectiveSchemas);
			dbModel.setSourceType(DatasourceType.ORACLE.name());
			try (
				PreparedStatement ps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_COLUMNS, effectiveSchemas.size()));
				PreparedStatement cps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_CONSTRAINTS, effectiveSchemas.size()));
				PreparedStatement ist = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_INDEXES, effectiveSchemas.size()));
				PreparedStatement sst = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_SEQUENCES, effectiveSchemas.size()));
				PreparedStatement ckps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_CHECK_CONSTRAINTS, effectiveSchemas.size()));
				PreparedStatement ccps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_COLUMN_COMMENTS, effectiveSchemas.size()));
				PreparedStatement tcps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_TABLE_COMMENTS, effectiveSchemas.size()));
				PreparedStatement vps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_VIEWS, effectiveSchemas.size()));
				PreparedStatement vdps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_VIEW_DEPS, effectiveSchemas.size()));
				PreparedStatement rps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_ROUTINES, effectiveSchemas.size()));
			) {
			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			RsHelper rsHelper = new RsHelper(rs);
			while (rs.next()) {
				String schemaName = rs.getString("SCHEMA_NAME");
				String tableName = rs.getString("TABLE_NAME");
				String columnName = rs.getString("COLUMN_NAME");
				Integer ordinalPosition = rsHelper.getInt("ORDINAL_POSITION");
				String columnType = rs.getString("COLUMN_TYPE");
				String dataType = rs.getString("DATA_TYPE");
				Integer maxLength = rsHelper.getIntegerFromNumber("CHARACTER_MAXIMUM_LENGTH");
				Integer numPrecision = rsHelper.getIntegerFromNumber("NUMERIC_PRECISION");
				Integer numScale = rsHelper.getIntegerFromNumber("NUMERIC_SCALE");
				if ("NUMBER".equalsIgnoreCase(dataType) && maxLength != null && maxLength == 0) {
					maxLength = null;
				}
				String columnDefault = rs.getString("COLUMN_DEFAULT");
				String nullable = rs.getString("IS_NULLABLE");

				DbTable table = dbModel.crateIfAbsent(tableName, schemaName);
				table.addColumn(DbColumn.builder()
					.name(columnName)
					.dataType(dataType)
					.ordinalPosition(ordinalPosition)
					.columnType(columnType)
					.maxLength(maxLength)
					.numPrecision(numPrecision)
					.numScale(numScale)
					.columnDefault(columnDefault)
					.nullable("Y".equalsIgnoreCase(nullable))
					.identity(false)
					.build());
			}

			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> cps.setString(i, schema)));
			ResultSet crs = cps.executeQuery();
			Map<String, ReferenceInfo> referenceInfos = new HashMap<>();
			Map<String, ContraintInfo> contraintInfos = new HashMap<>();
			while (crs.next()) {
				String schemaName = crs.getString("TABLE_SCHEMA");
				String tableName = crs.getString("TABLE_NAME");
				String constraintName = crs.getString("CONSTRAINT_NAME");
				String conType = crs.getString("CON_TYPE");
				String columnName = crs.getString("COLUMN_NAME");
				String refSchemaName = crs.getString("REFERENCED_SCHEMA_NAME");
				String refTableName = crs.getString("REFERENCED_TABLE_NAME");
				String refColumnName = crs.getString("REFERENCED_COLUMN_NAME");
				if ("p".equals(conType)) {
					DbTable table = dbModel.findTable(CommonHelpers.qualifiedName(schemaName, tableName))
						.orElseThrow(Exceptions.server("unknow-table").withExtra("schemaName", schemaName).withExtra("tableName", tableName).supplier());
					table.getPkColumnNames().add(columnName);
					table.setPkConstraintName(constraintName);
				} else if ("f".equals(conType)) {
					ReferenceInfo refInfo = referenceInfos.get(constraintName);
					if (refInfo == null) {
						refInfo = ReferenceInfo.builder()
							.constraintName(constraintName)
							.srcSchema(schemaName)
							.srcTableName(tableName)
							.srcColumnName(columnName)
							.refSchema(refSchemaName)
							.refTableName(refTableName)
							.refColumnName(refColumnName)
							.build();
						referenceInfos.put(constraintName, refInfo);
					} else {
						if (refInfo.getRefColumnNames().contains(refColumnName)) {
							continue;
						}
						refInfo.getSrcColumnNames().add(columnName);
						refInfo.getRefColumnNames().add(refColumnName);
					}
				} else if ("u".equals(conType)) {
					ContraintInfo contraintInfo = contraintInfos.get(constraintName);
					if (contraintInfo == null) {
						contraintInfo = ContraintInfo.builder()
							.constraintName(constraintName)
							.schema(schemaName)
							.tableName(tableName)
							.columnName(columnName)
							.build();
						contraintInfos.put(constraintName, contraintInfo);
					} else {
						contraintInfo.getColumnNames().add(columnName);
					}
				}
			}
			dbModel.getReferenceInfos().addAll(referenceInfos.values());
			dbModel.getContraintInfos().addAll(contraintInfos.values());

			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ist.setString(i, schema)));
			ResultSet irs = ist.executeQuery();
			IndexInfo cur = null;
			while (irs.next()) {
				String schemaName = irs.getString("SCHEMA_NAME");
				String tableName = irs.getString("TABLE_NAME");
				String indexName = irs.getString("INDEX_NAME");
				String columnName = irs.getString("COLUMN_NAME");
				boolean isUnique = irs.getBoolean("IS_UNIQUE");
				String indexType = irs.getString("INDEX_TYPE");
				String qualifiedTableName = CommonHelpers.qualifiedName(schemaName, tableName);
				if (cur == null || !qualifiedTableName.equals(cur.qualifiedTableName()) || !indexName.equals(cur.getIndexName())) {
					if (cur != null) {
						CommonOps.getOrInit(dbModel.getIndexes(), cur.qualifiedTableName(), HashMap::new).put(cur.getIndexName(), cur);
					}
					cur = new IndexInfo(schemaName, tableName, indexName, isUnique, indexType);
				}
				cur.getColumns().add(columnName);
			}
			if (cur != null) {
				CommonOps.getOrInit(dbModel.getIndexes(), cur.qualifiedTableName(), HashMap::new).put(cur.getIndexName(), cur);
			}

			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> sst.setString(i, schema)));
			ResultSet srs = sst.executeQuery();
			rsHelper = new RsHelper(srs);
			while (srs.next()) {
				String schemaName = srs.getString("SCHEMA_NAME");
				String sequenceName = srs.getString("SEQUENCE_NAME");
				Long startValue = rsHelper.getLongClamped("START_VALUE");
				Long minValue = rsHelper.getLongClamped("MIN_VALUE");
				Long maxValue = rsHelper.getLongClamped("MAX_VALUE");
				Long incrementBy = rsHelper.getLongClamped("INCREMENT_BY");
				boolean cycle = srs.getBoolean("CYCLE");
				Long cacheSize = rsHelper.getLongClamped("CACHE_SIZE");
				Long lastValue = rsHelper.getLongClamped("LAST_VALUE");
				DbSequence seq = DbSequence.builder()
					.schema(schemaName)
					.name(sequenceName)
					.startValue(startValue)
					.minValue(minValue)
					.maxValue(maxValue)
					.incrementBy(incrementBy)
					.cycle(cycle)
					.cacheSize(cacheSize)
					.lastValue(lastValue)
					.build();
				dbModel.getSequences().add(seq);
			}

			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ckps.setString(i, schema)));
			ResultSet ckrs = ckps.executeQuery();
			while (ckrs.next()) {
				String schemaName = ckrs.getString("TABLE_SCHEMA");
				String tableName = ckrs.getString("TABLE_NAME");
				String constraintName = ckrs.getString("CONSTRAINT_NAME");
				String condef = ckrs.getString("CONDEF");
				CheckConstraint checkConstraint = CheckConstraint.builder()
					.schema(schemaName)
					.tableName(tableName)
					.constraintName(constraintName)
					.condef(condef)
					.build();
				dbModel.getCheckConstraints().add(checkConstraint);
			}

			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ccps.setString(i, schema)));
			ResultSet ccrs = ccps.executeQuery();
			while (ccrs.next()) {
				String schemaName = ccrs.getString("SCHEMA_NAME");
				String tableName = ccrs.getString("TABLE_NAME");
				String columnName = ccrs.getString("COLUMN_NAME");
				String comments = ccrs.getString("COMMENTS");
				dbModel.findTable(CommonHelpers.qualifiedName(schemaName, tableName))
					.flatMap(table -> table.findColumn(columnName))
					.ifPresent(column -> column.setComment(comments));
			}

			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> tcps.setString(i, schema)));
			ResultSet tcrs = tcps.executeQuery();
			while (tcrs.next()) {
				String schemaName = tcrs.getString("SCHEMA_NAME");
				String tableName = tcrs.getString("TABLE_NAME");
				String comments = tcrs.getString("COMMENTS");
				dbModel.findTable(CommonHelpers.qualifiedName(schemaName, tableName))
					.ifPresent(table -> table.setComment(comments));
			}

			Map<String, DbView> viewsByName = new HashMap<>();
			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> vps.setString(i, schema)));
			ResultSet vrs = vps.executeQuery();
			while (vrs.next()) {
				String schemaName = vrs.getString("SCHEMA_NAME");
				String viewName = vrs.getString("VIEW_NAME");
				String definition = vrs.getString("DEFINITION");
				DbView view = DbView.builder()
					.schema(schemaName)
					.name(viewName)
					.definition(definition)
					.dependsOnViews(new HashSet<>())
					.build();
				viewsByName.put(view.qualifiedName(), view);
				dbModel.getViews().add(view);
			}
			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> vdps.setString(i, schema)));
			ResultSet vdrs = vdps.executeQuery();
			while (vdrs.next()) {
				String viewSchema = vdrs.getString("VIEW_SCHEMA");
				String viewName = vdrs.getString("VIEW_NAME");
				String depSchema = vdrs.getString("DEP_SCHEMA");
				String depName = vdrs.getString("DEP_NAME");
				DbView view = viewsByName.get(CommonHelpers.qualifiedName(viewSchema, viewName));
				String depQualified = CommonHelpers.qualifiedName(depSchema, depName);
				if (view != null && viewsByName.containsKey(depQualified)) {
					view.getDependsOnViews().add(depQualified);
				}
			}

			CommonHelpers.consumeIndexed(effectiveSchemas, 1, Exceptions.wrapBiConsumer((i, schema) -> rps.setString(i, schema)));
			ResultSet rrs = rps.executeQuery();
			List<String[]> routineKeys = new LinkedList<>();
			while (rrs.next()) {
				routineKeys.add(new String[] {
					rrs.getString("SCHEMA_NAME"),
					rrs.getString("ROUTINE_NAME"),
					rrs.getString("ROUTINE_TYPE")
				});
			}
			rrs.close();
			for (String[] key : routineKeys) {
				String schemaName = key[0];
				String routineName = key[1];
				String routineType = key[2];
				String definition = loadOracleRoutineDefinition(con, schemaName, routineName, routineType);
				if (definition == null || definition.isBlank()) {
					log.warn("Skipping Oracle {} {}.{} — unable to read definition", routineType, schemaName, routineName);
					continue;
				}
				dbModel.getFunctions().add(DbFunction.builder()
					.schema(schemaName)
					.name(routineName)
					.routineType(routineType)
					.definition(definition)
					.build());
			}
			dbModel.build();
			}
		} catch (BaseRuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw Exceptions.server("unable-to-build-dbmodel").withCause(e).get();
		}
		return dbModel;
	}

	private String loadOracleRoutineDefinition(Connection con, String schema, String name, String type) {
		try (PreparedStatement ps = con.prepareStatement(SQL_FOR_ROUTINE_DDL)) {
			ps.setString(1, type);
			ps.setString(2, name);
			ps.setString(3, schema);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String ddl = readClobOrString(rs, 1);
					if (ddl != null && !ddl.isBlank()) {
						return normalizeOracleRoutineDdl(ddl);
					}
				}
			}
		} catch (SQLException e) {
			log.warn("DBMS_METADATA.GET_DDL failed for {} {}.{}: {}", type, schema, name, e.getMessage());
		}
		return loadOracleRoutineFromSource(con, schema, name, type);
	}

	private String loadOracleRoutineFromSource(Connection con, String schema, String name, String type) {
		StringBuilder sb = new StringBuilder();
		try (PreparedStatement ps = con.prepareStatement(SQL_FOR_ROUTINE_SOURCE)) {
			ps.setString(1, schema);
			ps.setString(2, name);
			ps.setString(3, type);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String line = rs.getString("TEXT");
					if (line != null) {
						sb.append(line);
					}
				}
			}
		} catch (SQLException e) {
			log.warn("ALL_SOURCE failed for {} {}.{}: {}", type, schema, name, e.getMessage());
			return null;
		}
		if (sb.length() == 0) {
			return null;
		}
		String body = sb.toString().trim();
		if (!body.regionMatches(true, 0, "CREATE", 0, 6)) {
			body = "CREATE OR REPLACE " + body;
		}
		return normalizeOracleRoutineDdl(body);
	}

	static String normalizeOracleRoutineDdl(String ddl) {
		if (ddl == null) {
			return null;
		}
		String trimmed = ddl.trim();
		// DBMS_METADATA often ends with a trailing slash on its own line
		if (trimmed.endsWith("\n/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 2).trim();
		} else if (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return trimmed;
	}

	private static String readClobOrString(ResultSet rs, int index) throws SQLException {
		try {
			java.sql.Clob clob = rs.getClob(index);
			if (clob != null) {
				long len = clob.length();
				if (len > Integer.MAX_VALUE) {
					throw new SQLException("Routine DDL CLOB too large: " + len);
				}
				return clob.getSubString(1, (int) len);
			}
		} catch (SQLException ignored) {
			// fall through to getString
		}
		return rs.getString(index);
	}

	private Set<String> resolveSchemas(Connection con) throws SQLException {
		if (schemas != null && !schemas.isEmpty()) {
			Set<String> normalized = schemas.stream()
				.filter(s -> s != null && !s.trim().isEmpty())
				.map(s -> s.trim().toUpperCase())
				.collect(Collectors.toCollection(HashSet::new));
			if (!normalized.isEmpty()) {
				return normalized;
			}
		}
		if (username != null && !username.trim().isEmpty()) {
			String schemaFromUser = username.trim().toUpperCase();
			log.info("Oracle datasource {} using username {} as default schema", getName(), schemaFromUser);
			return Set.of(schemaFromUser);
		}
		String currentSchema = con.getSchema();
		if (currentSchema == null || currentSchema.isBlank()) {
			try (Statement st = con.createStatement();
				 ResultSet rs = st.executeQuery("SELECT USER FROM DUAL")) {
				if (rs.next()) {
					currentSchema = rs.getString(1);
				}
			}
		}
		if (currentSchema == null || currentSchema.isBlank()) {
			throw Exceptions.badRequest("oracle-schema-required")
				.withExtra("name", getName())
				.withExtra("hint", "Set the Schema field to the Oracle owner name(s) to back up, or connect with the target schema user")
				.get();
		}
		log.info("Oracle datasource {} using connected user {} as default schema", getName(), currentSchema);
		return Set.of(currentSchema.trim().toUpperCase());
	}

	@Override
	public DDLService ddlService() throws SQLException {
		return new DDLServiceOracle(getDataSource().getConnection());
	}

	@Override
	public DMLService dmlService() throws SQLException {
		return new DMLServiceOracle(getDataSource(), globalLock);
	}
}
