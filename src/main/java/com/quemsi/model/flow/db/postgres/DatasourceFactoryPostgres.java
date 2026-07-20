package com.quemsi.model.flow.db.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.sql.DataSource;

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
import com.quemsi.model.flow.db.sql.DbEnumType;
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
public class DatasourceFactoryPostgres implements DataSourceFactory{
    private static final String SQL_FOR_COLUMNS = """
select 
	c.table_schema as TABLE_SCHEMA, c.table_name, c.column_name, c.ordinal_position,
	c.character_maximum_length, c.udt_name as column_type, c.udt_name as data_type, c.character_octet_length, c.numeric_precision, c.numeric_scale,
	c.column_default, c.is_nullable
from information_schema.columns c
where c.table_schema in {inValues}
	and not exists (select v.table_name from INFORMATION_SCHEMA.views v where v.table_catalog = c.table_catalog and v.table_name = c.table_name )
order by c.table_catalog, c.table_schema, c.table_name, c.ordinal_position
;
""";

	private static final String SQL_FOR_CONSTRAINTS = """
select
  ns.nspname as table_schema, rel.relname as table_name, con.conname as constraint_name, con.contype as con_type,
  pg_catalog.pg_get_constraintdef(con.oid, true) as con_def,
  a.attname as column_name, nsf.nspname as referenced_schema_name, relf.relname as referenced_table_name, af.attname as referenced_column_name
from pg_catalog.pg_constraint con
join pg_catalog.pg_namespace ns on ns.oid = con.connamespace
join pg_catalog.pg_class rel on rel.oid = con.conrelid
join lateral unnest(con.conkey) with ordinality as ak(attnum, ord) on true
join pg_attribute a on a.attrelid = con.conrelid and a.attnum = ak.attnum
left join pg_catalog.pg_class relf on relf.oid = con.confrelid
left join pg_catalog.pg_namespace nsf on nsf.oid = relf.relnamespace
left join lateral unnest(con.confkey) with ordinality as cf(attnum, ord) on cf.ord = ak.ord
left join pg_attribute af
  on af.attrelid = con.confrelid and af.attnum = cf.attnum
where ns.nspname in {inValues}
order by rel.relname, con.conname, ak.ord	
;
	""";
    private static final String SQL_FOR_INDEXES = """
select * from (
  select 
    ns.nspname as schema_name,
    t.relname as table_name,
    i.relname as index_name,
    a.attname as column_name,
    array_position(ix.indkey, a.attnum) + 1 SEQ_IN_INDEX,
    not ix.indisunique NON_UNIQUE,
    it.index_type
  from
    pg_class t,
    pg_class i,
    pg_index ix,
    pg_attribute a,
    pg_namespace ns,
    (
    	SELECT tab.relname as table_name, cls.relname as index_name, am.amname as index_type
		FROM pg_index idx 
		JOIN pg_class cls ON cls.oid=idx.indexrelid
		JOIN pg_class tab ON tab.oid=idx.indrelid
		JOIN pg_am am ON am.oid=cls.relam
    ) it
  where
    t.oid = ix.indrelid
    and i.oid = ix.indexrelid
    and a.attrelid = t.oid
    and a.attnum = ANY(ix.indkey)
    and t.relkind = 'r'
    and t.relnamespace = ns.oid
    and i.relname not in (select con.conname from pg_constraint con)
    and it.table_name = t.relname
    and it.index_name = i.relname
    and ns.nspname in {inValues}
) iq
order by
  iq.table_name, iq.index_name, iq.seq_in_index
;
            """;
	private static final String SQL_FOR_SEQUENCES = """
select 
	s.schemaname as schema_name, s.sequencename as sequence_name, s.start_value, s.min_value, s.max_value, 
	s.increment_by, s.cycle, s.cache_size, s.last_value
from pg_sequences s
where s.schemaname in {inValues}
;
			""";

	private static final String SQL_FOR_CHECK_CONSTRAINTS = """
select 
	ns.nspname as table_schema, rel.relname as table_name, con.conname as constraint_name,
	pg_catalog.pg_get_constraintdef(con.oid, true) as condef 
from pg_catalog.pg_constraint con
inner join pg_catalog.pg_namespace ns on con.connamespace = ns.oid
inner join pg_catalog.pg_class rel on rel.oid = con.conrelid
where con.contype = 'c' and ns.nspname in {inValues}
;
			""";

	private static final String SQL_FOR_VIEWS = """
select
	n.nspname as schema_name,
	c.relname as view_name,
	pg_catalog.pg_get_viewdef(c.oid, true) as definition
from pg_catalog.pg_class c
inner join pg_catalog.pg_namespace n on n.oid = c.relnamespace
where c.relkind = 'v' and n.nspname in {inValues}
order by n.nspname, c.relname
;
			""";

	private static final String SQL_FOR_VIEW_DEPS = """
select distinct
	nv.nspname as view_schema,
	cv.relname as view_name,
	nd.nspname as dep_schema,
	cd.relname as dep_name
from pg_catalog.pg_depend d
inner join pg_catalog.pg_rewrite r on d.objid = r.oid
inner join pg_catalog.pg_class cv on r.ev_class = cv.oid
inner join pg_catalog.pg_namespace nv on cv.relnamespace = nv.oid
inner join pg_catalog.pg_class cd on d.refobjid = cd.oid
inner join pg_catalog.pg_namespace nd on cd.relnamespace = nd.oid
where cv.relkind = 'v'
  and cd.relkind = 'v'
  and cv.oid <> cd.oid
  and d.deptype = 'n'
  and nv.nspname in {inValues}
;
			""";

	/**
	 * Routines referenced by views in the configured schemas (excludes pg_catalog / information_schema).
	 * Aggregates cannot use {@code pg_get_functiondef}; they are reconstructed from {@code pg_aggregate}.
	 * MATERIALIZED CTEs keep the planner from evaluating {@code pg_get_functiondef} on aggregates.
	 */
	static final String SQL_FOR_VIEW_FUNCTIONS = """
with view_dep_procs as materialized (
	select distinct
		p.oid,
		p.prokind,
		n.nspname as schema_name,
		p.proname as function_name
	from pg_catalog.pg_depend d
	inner join pg_catalog.pg_rewrite r on d.objid = r.oid
	inner join pg_catalog.pg_class c on r.ev_class = c.oid
	inner join pg_catalog.pg_namespace nv on c.relnamespace = nv.oid
	inner join pg_catalog.pg_proc p on d.refobjid = p.oid
	inner join pg_catalog.pg_namespace n on p.pronamespace = n.oid
	where c.relkind = 'v'
	  and d.refclassid = 'pg_proc'::regclass
	  and d.deptype = 'n'
	  and nv.nspname in {inValues}
	  and n.nspname not in ('pg_catalog', 'information_schema')
),
expanded as materialized (
	select oid, prokind, schema_name, function_name
	from view_dep_procs
	union
	select sp.oid, sp.prokind, sn.nspname, sp.proname
	from view_dep_procs vp
	inner join pg_catalog.pg_aggregate a on a.aggfnoid = vp.oid
	inner join pg_catalog.pg_proc sp on sp.oid = a.aggtransfn
	inner join pg_catalog.pg_namespace sn on sn.oid = sp.pronamespace
	where vp.prokind = 'a'
	  and sn.nspname not in ('pg_catalog', 'information_schema')
	union
	select fp.oid, fp.prokind, fn.nspname, fp.proname
	from view_dep_procs vp
	inner join pg_catalog.pg_aggregate a on a.aggfnoid = vp.oid
	inner join pg_catalog.pg_proc fp on fp.oid = a.aggfinalfn
	inner join pg_catalog.pg_namespace fn on fn.oid = fp.pronamespace
	where vp.prokind = 'a'
	  and a.aggfinalfn <> 0
	  and fn.nspname not in ('pg_catalog', 'information_schema')
),
plain as materialized (
	select oid, prokind, schema_name, function_name
	from expanded
	where prokind in ('f', 'p')
)
select
	schema_name,
	function_name,
	pg_catalog.pg_get_function_identity_arguments(oid) as identity_arguments,
	pg_catalog.pg_get_functiondef(oid) as definition,
	case when prokind = 'p' then 'PROCEDURE' else 'FUNCTION' end as routine_type,
	0 as create_order
from plain
union all
select
	e.schema_name,
	e.function_name,
	pg_catalog.pg_get_function_identity_arguments(e.oid) as identity_arguments,
	(
		select format(
			'CREATE AGGREGATE %s (SFUNC = %s, STYPE = %s%s%s%s%s)',
			a.aggfnoid::regprocedure,
			a.aggtransfn,
			a.aggtranstype::regtype,
			coalesce(', SORTOP = ' || nullif(a.aggsortop, 0)::regoper, ''),
			coalesce(', INITCOND = ' || quote_literal(a.agginitval), ''),
			coalesce(', FINALFUNC = ' || nullif(a.aggfinalfn, 0)::regproc, ''),
			case when a.aggfinalextra then ', FINALFUNC_EXTRA' else '' end
		)
		from pg_catalog.pg_aggregate a
		where a.aggfnoid = e.oid
	) as definition,
	'AGGREGATE' as routine_type,
	1 as create_order
from expanded e
where e.prokind = 'a'
order by create_order, schema_name, function_name
;
			""";

	static final String SQL_FOR_ENUM_TYPES = """
select
	n.nspname as schema_name,
	t.typname as type_name,
	e.enumlabel as enum_label,
	e.enumsortorder as sort_order
from pg_catalog.pg_type t
inner join pg_catalog.pg_namespace n on n.oid = t.typnamespace
inner join pg_catalog.pg_enum e on e.enumtypid = t.oid
where t.typtype = 'e'
  and n.nspname in {inValues}
order by n.nspname, t.typname, e.enumsortorder
;
			""";

	static final String SQL_FOR_DOMAIN_TYPES = """
select
	n.nspname as schema_name,
	t.typname as type_name,
	pg_catalog.format_type(t.typbasetype, t.typtypmod) as base_type,
	t.typnotnull as not_null,
	t.typdefault as type_default,
	con.conname as check_constraint_name,
	pg_catalog.pg_get_constraintdef(con.oid, true) as check_constraint_def
from pg_catalog.pg_type t
inner join pg_catalog.pg_namespace n on n.oid = t.typnamespace
left join pg_catalog.pg_constraint con on con.contypid = t.oid and con.contype = 'c'
where t.typtype = 'd'
  and n.nspname in {inValues}
order by n.nspname, t.typname
;
			""";

	/** Overwrite information_schema base types with domain typnames where applicable. */
	static final String SQL_FOR_DOMAIN_COLUMNS = """
select
	n.nspname as table_schema,
	c.relname as table_name,
	a.attname as column_name,
	t.typname as domain_name
from pg_catalog.pg_attribute a
inner join pg_catalog.pg_class c on c.oid = a.attrelid
inner join pg_catalog.pg_namespace n on n.oid = c.relnamespace
inner join pg_catalog.pg_type t on t.oid = a.atttypid
where a.attnum > 0
  and not a.attisdropped
  and c.relkind = 'r'
  and t.typtype = 'd'
  and n.nspname in {inValues}
;
			""";

	/**
	 * User triggers on tables in configured schemas.
	 * Function definition is only loaded for non-catalog routines (prokind f/p).
	 */
	static final String SQL_FOR_TRIGGERS = """
with trig as materialized (
	select
		ns.nspname as table_schema,
		c.relname as table_name,
		t.tgname as trigger_name,
		pg_catalog.pg_get_triggerdef(t.oid, true) as definition,
		pn.nspname as function_schema,
		p.proname as function_name,
		p.oid as function_oid,
		p.prokind as function_kind
	from pg_catalog.pg_trigger t
	inner join pg_catalog.pg_class c on c.oid = t.tgrelid
	inner join pg_catalog.pg_namespace ns on ns.oid = c.relnamespace
	inner join pg_catalog.pg_proc p on p.oid = t.tgfoid
	inner join pg_catalog.pg_namespace pn on pn.oid = p.pronamespace
	where not t.tgisinternal
	  and ns.nspname in {inValues}
)
select
	table_schema,
	table_name,
	trigger_name,
	definition,
	function_schema,
	function_name,
	case
		when function_schema not in ('pg_catalog', 'information_schema')
			and function_kind in ('f', 'p')
		then pg_catalog.pg_get_functiondef(function_oid)
		else null
	end as function_definition,
	pg_catalog.pg_get_function_identity_arguments(function_oid) as identity_arguments
from trig
order by table_schema, table_name, trigger_name
;
			""";

	protected static final String SQL_FOR_SCHEMA = "select nspname from pg_catalog.pg_namespace ns where ns.nspname = ?;";
	
    private String name;
	private String dbName;
	private String url;
	private String username;
	private String password;
	private boolean readOnly;
	private Set<String> schemas;
	private HikariDataSource instance;
	
	@Override
	public DatasourceType type() {
		return DatasourceType.POSTGRES;
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
			config.setPoolName("HikariPool-" + (this.name != null ? this.name : "Postgres")); /* Named pool for monitoring */
			applyPostgresBatchDataSourceProperties(config);
			HikariDataSource ds =new HikariDataSource(config);
			instance = ds;
		}
		return instance;
	}

	/**
	 * Enables PostgreSQL JDBC multi-value INSERT rewriting for PreparedStatement batches.
	 */
	static void applyPostgresBatchDataSourceProperties(HikariConfig config) {
		config.addDataSourceProperty("reWriteBatchedInserts", "true");
	}

    @Override
    public DbModel getDbModel(Consumer<LogMessage> progress) {
		long startTime = System.currentTimeMillis();
        DbModel dbModel = new DbModel();
		dbModel.setSchemas(getSchemas());
		dbModel.setSourceType(DatasourceType.POSTGRES.name());
		reportProgress(progress, LogMessage.info("Loading model for schemas: {}", getSchemas()));
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement ps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_COLUMNS, schemas.size()));
			PreparedStatement cps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_CONSTRAINTS, schemas.size()));
			PreparedStatement ist = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_INDEXES, schemas.size()));
			PreparedStatement sst = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_SEQUENCES, schemas.size()));
			PreparedStatement ckps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_CHECK_CONSTRAINTS, schemas.size()));
			PreparedStatement vps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_VIEWS, schemas.size()));
			PreparedStatement vdps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_VIEW_DEPS, schemas.size()));
			PreparedStatement vfps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_VIEW_FUNCTIONS, schemas.size()));
			PreparedStatement eps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_ENUM_TYPES, schemas.size()));
			PreparedStatement dps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_DOMAIN_TYPES, schemas.size()));
			PreparedStatement dcps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_DOMAIN_COLUMNS, schemas.size()));
			PreparedStatement tps = con.prepareStatement(CommonHelpers.addInParameter(SQL_FOR_TRIGGERS, schemas.size()));
		){
			reportProgress(progress, LogMessage.info("Loading tables and columns..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			RsHelper rsHelper = new RsHelper(rs);
			while(rs.next()){
				String schemaName = rs.getString("TABLE_SCHEMA");
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
				DbTable table = dbModel.crateIfAbsent(tableName, schemaName);
				table.addColumn(DbColumn.builder().name(columnName).dataType(dataType).ordinalPosition(ordinalPosition).columnType(columnType).maxLength(maxLength).numPrecision(numPrecision).numScale(numScale).columnDefault(columnDefault).nullable(CommonOps.isTrue(nullable)).identity(CommonOps.isTrue(isIdentity)).build());
			}
			reportProgress(progress, LogMessage.info("Loaded {} tables", dbModel.getTables().size()));

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
				boolean nonUnique = irs.getBoolean("NON_UNIQUE");
				String indexType = irs.getString("INDEX_TYPE");
				String qualifiedTableName = CommonHelpers.qualifiedName(schemaName, tableName);
				if(cur == null || !qualifiedTableName.equals(cur.qualifiedTableName()) || !indexName.equals(cur.getIndexName())){
					if(cur != null){
						CommonOps.getOrInit(dbModel.getIndexes(), cur.qualifiedTableName(), () -> new HashMap<>()).put(cur.getIndexName(), cur);
					}
					cur = new IndexInfo(schemaName, tableName, indexName, !nonUnique, indexType);
				}
				cur.getColumns().add(columnName);
			}
			if(cur != null){
				CommonOps.getOrInit(dbModel.getIndexes(), cur.getTableName(), HashMap::new).put(cur.getIndexName(), cur);
			}
			reportProgress(progress, LogMessage.info("Loading sequences..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> sst.setString(i, schema)));
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
			reportProgress(progress, LogMessage.info("Loading check constraints..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ckps.setString(i, schema)));
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
			reportProgress(progress, LogMessage.info("Loading enum types..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> eps.setString(i, schema)));
			ResultSet ers = eps.executeQuery();
			Map<String, DbEnumType> enumByName = new HashMap<>();
			while (ers.next()) {
				String schemaName = ers.getString("SCHEMA_NAME");
				String typeName = ers.getString("TYPE_NAME");
				String label = ers.getString("ENUM_LABEL");
				String key = CommonHelpers.qualifiedName(schemaName, typeName);
				DbEnumType enumType = enumByName.get(key);
				if (enumType == null) {
					enumType = DbEnumType.builder()
						.schema(schemaName)
						.name(typeName)
						.labels(new java.util.ArrayList<>())
						.build();
					enumByName.put(key, enumType);
					dbModel.getEnumTypes().add(enumType);
				}
				enumType.getLabels().add(label);
			}
			reportProgress(progress, LogMessage.info("Loaded {} enum types", dbModel.getEnumTypes().size()));
			reportProgress(progress, LogMessage.info("Loading domain types..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> dps.setString(i, schema)));
			ResultSet drs = dps.executeQuery();
			while (drs.next()) {
				String schemaName = drs.getString("SCHEMA_NAME");
				String typeName = drs.getString("TYPE_NAME");
				String baseType = drs.getString("BASE_TYPE");
				boolean notNull = drs.getBoolean("NOT_NULL");
				String typeDefault = drs.getString("TYPE_DEFAULT");
				String checkName = drs.getString("CHECK_CONSTRAINT_NAME");
				String checkDef = drs.getString("CHECK_CONSTRAINT_DEF");
				dbModel.getDomainTypes().add(DbDomainType.builder()
					.schema(schemaName)
					.name(typeName)
					.baseType(baseType)
					.notNull(notNull)
					.defaultExpression(typeDefault)
					.checkConstraintName(checkName)
					.checkConstraintDef(checkDef)
					.build());
			}
			reportProgress(progress, LogMessage.info("Loaded {} domain types", dbModel.getDomainTypes().size()));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> dcps.setString(i, schema)));
			ResultSet dcrs = dcps.executeQuery();
			while (dcrs.next()) {
				String schemaName = dcrs.getString("TABLE_SCHEMA");
				String tableName = dcrs.getString("TABLE_NAME");
				String columnName = dcrs.getString("COLUMN_NAME");
				String domainName = dcrs.getString("DOMAIN_NAME");
				dbModel.findTable(CommonHelpers.qualifiedName(schemaName, tableName)).ifPresent(table -> {
					DbColumn column = table.column(columnName);
					if (column != null) {
						column.setColumnType(domainName);
						column.setDataType(domainName);
						/* Domain length/precision live on the base type; clear so DDL uses domain name only. */
						column.setMaxLength(null);
						column.setNumPrecision(null);
						column.setNumScale(null);
					}
				});
			}
			reportProgress(progress, LogMessage.info("Loading views..."));
			Map<String, DbView> viewsByName = new HashMap<>();
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> vps.setString(i, schema)));
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
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> vdps.setString(i, schema)));
			ResultSet vdrs = vdps.executeQuery();
			while (vdrs.next()) {
				String viewSchema = vdrs.getString("VIEW_SCHEMA");
				String viewName = vdrs.getString("VIEW_NAME");
				String depSchema = vdrs.getString("DEP_SCHEMA");
				String depName = vdrs.getString("DEP_NAME");
				String viewQualified = CommonHelpers.qualifiedName(viewSchema, viewName);
				String depQualified = CommonHelpers.qualifiedName(depSchema, depName);
				DbView view = viewsByName.get(viewQualified);
				// Only record deps that are also in this backup model
				if (view != null && viewsByName.containsKey(depQualified)) {
					view.getDependsOnViews().add(depQualified);
				}
			}
			reportProgress(progress, LogMessage.info("Loaded {} views", dbModel.getViews().size()));
			reportProgress(progress, LogMessage.info("Loading routines..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> vfps.setString(i, schema)));
			ResultSet vfrs = vfps.executeQuery();
			Set<String> seenFunctions = new HashSet<>();
			while (vfrs.next()) {
				String schemaName = vfrs.getString("SCHEMA_NAME");
				String functionName = vfrs.getString("FUNCTION_NAME");
				String identityArguments = vfrs.getString("IDENTITY_ARGUMENTS");
				String definition = vfrs.getString("DEFINITION");
				String routineType = vfrs.getString("ROUTINE_TYPE");
				String key = CommonHelpers.qualifiedName(schemaName, functionName)
					+ "(" + (identityArguments != null ? identityArguments : "") + ")";
				if (!seenFunctions.add(key)) {
					continue;
				}
				dbModel.getFunctions().add(DbFunction.builder()
					.schema(schemaName)
					.name(functionName)
					.routineType(routineType != null ? routineType : DbFunction.TYPE_FUNCTION)
					.identityArguments(identityArguments)
					.definition(definition)
					.build());
			}
			reportProgress(progress, LogMessage.info("Loaded {} routines from views", dbModel.getFunctions().size()));
			reportProgress(progress, LogMessage.info("Loading triggers..."));
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> tps.setString(i, schema)));
			ResultSet trs = tps.executeQuery();
			while (trs.next()) {
				String tableSchema = trs.getString("TABLE_SCHEMA");
				String tableName = trs.getString("TABLE_NAME");
				String triggerName = trs.getString("TRIGGER_NAME");
				String definition = trs.getString("DEFINITION");
				String functionSchema = trs.getString("FUNCTION_SCHEMA");
				String functionName = trs.getString("FUNCTION_NAME");
				String functionDefinition = trs.getString("FUNCTION_DEFINITION");
				String identityArguments = trs.getString("IDENTITY_ARGUMENTS");
				dbModel.getTriggers().add(DbTrigger.builder()
					.schema(tableSchema)
					.tableName(tableName)
					.name(triggerName)
					.definition(definition)
					.functionSchema(functionSchema)
					.functionName(functionName)
					.build());
				if (functionDefinition != null && !functionDefinition.isBlank()) {
					String key = CommonHelpers.qualifiedName(functionSchema, functionName)
						+ "(" + (identityArguments != null ? identityArguments : "") + ")";
					if (seenFunctions.add(key)) {
						dbModel.getFunctions().add(DbFunction.builder()
							.schema(functionSchema)
							.name(functionName)
							.routineType(DbFunction.TYPE_FUNCTION)
							.identityArguments(identityArguments)
							.definition(functionDefinition)
							.build());
					}
				}
			}
			reportProgress(progress, LogMessage.info("Loaded {} triggers ({} routines total)", dbModel.getTriggers().size(), dbModel.getFunctions().size()));
			reportProgress(progress, LogMessage.info("Building model graph..."));
			dbModel.build();
			reportProgress(progress, LogMessage.info("Database model ready ({} tables, {} views) in {} secs", dbModel.getTables().size(), dbModel.getViews().size(), Duration.ofMillis(System.currentTimeMillis() - startTime).toString()));
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

    @Override
	public DDLService ddlService() throws SQLException {
		return new DDLServicePostgres(getDataSource().getConnection());
	}

	@Override
	public DMLService dmlService() throws SQLException {
	  return new DMLServicePostgres(getDataSource());
	}
}
