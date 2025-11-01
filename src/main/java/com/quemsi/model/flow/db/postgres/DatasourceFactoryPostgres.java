package com.quemsi.model.flow.db.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
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
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
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
where c.table_catalog = ? and c.table_schema  = ?
	and not exists (select v.table_name from INFORMATION_SCHEMA.views v where v.table_catalog = c.table_catalog and v.table_name = c.table_name )
order by c.table_catalog, c.table_schema, c.table_name, c.ordinal_position
;            """;

	private static final String SQL_FOR_CONSTRAINTS = """
select
  ns.nspname as table_schema,
  rel.relname as table_name,
  con.conname as constraint_name,
  con.contype as con_type,
  pg_catalog.pg_get_constraintdef(con.oid, true) as con_def,
  a.attname as column_name,
  nsf.nspname as referenced_schema_name,
  relf.relname as referenced_table_name,
  af.attname as referenced_column_name
from pg_catalog.pg_constraint con
join pg_catalog.pg_namespace ns on ns.oid = con.connamespace
join pg_catalog.pg_class rel on rel.oid = con.conrelid
join lateral unnest(con.conkey) with ordinality as ak(attnum, ord) on true
join pg_attribute a
  on a.attrelid = con.conrelid and a.attnum = ak.attnum
left join pg_catalog.pg_class relf on relf.oid = con.confrelid
left join pg_catalog.pg_namespace nsf on nsf.oid = relf.relnamespace
left join lateral unnest(con.confkey) with ordinality as cf(attnum, ord) on cf.ord = ak.ord
left join pg_attribute af
  on af.attrelid = con.confrelid and af.attnum = cf.attnum
where ns.nspname = ?
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
    and ns.nspname = ?
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
where s.schemaname = ?;
			""";

	protected static final String SQL_FOR_SCHEMA = "select nspname from pg_catalog.pg_namespace ns where ns.nspname = ?;";
	
    private String name;
	private String dbName;
	private String url;
	private String username;
	private String password;
	private String schema;
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
			/* Connection pool settings to prevent exhaustion */
			config.setMaximumPoolSize(20);  /* Reasonable max connections per datasource */
			config.setMinimumIdle(2);      /* Keep minimum idle connections */
			config.setIdleTimeout(300000); /* 5 minutes idle timeout */
			config.setMaxLifetime(1200000); /* 20 minutes max lifetime */
			config.setConnectionTimeout(30000); /* 30 seconds connection timeout */
			config.setLeakDetectionThreshold(30000); /* 30 seconds leak detection */
			config.setValidationTimeout(5000); /* 5 seconds validation timeout */
			config.setPoolName("HikariPool-" + (this.name != null ? this.name : "Postgres")); /* Named pool for monitoring */
			HikariDataSource ds =new HikariDataSource(config);
			instance = ds;
		}
		return instance;
	}

    @Override
    public DbModel getDbModel() {
        DbModel dbModel = new DbModel();
		dbModel.setSchema(getSchema());
		dbModel.setSourceType(DatasourceType.POSTGRES.name());
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement ps = con.prepareStatement(SQL_FOR_COLUMNS);
			PreparedStatement cps = con.prepareStatement(SQL_FOR_CONSTRAINTS);
			PreparedStatement ist = con.prepareStatement(SQL_FOR_INDEXES);
			PreparedStatement sst = con.prepareStatement(SQL_FOR_SEQUENCES);
		){
			ps.setString(1, dbName);
			ps.setString(2, schema);
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

			cps.setString(1, schema);
			ResultSet crs = cps.executeQuery();
			rsHelper = new RsHelper(crs);
			Map<String, ReferenceInfo> referenceInfos = new HashMap<>();
			while(crs.next()){
				String schemaName = crs.getString("TABLE_SCHEMA");
				String tableName = crs.getString("TABLE_NAME");
				String constraintName = crs.getString("CONSTRAINT_NAME");
				String conType = crs.getString("CON_TYPE");
				String conDef = crs.getString("CON_DEF");
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
				}
			}
			dbModel.getReferenceInfos().addAll(referenceInfos.values());
			ist.setString(1, schema);
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
						CommonOps.getOrInit(dbModel.getIndexes(), cur.qualifiedTableName(), () -> new HashMap<>()).put(cur.getIndexName(), cur);
					}
					cur = new IndexInfo(schemaName, tableName, indexName, !nonUnique, indexType);
				}
				cur.getColumns().add(columnName);
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
		return new DDLServicePostgres(getDataSource().getConnection());
	}

	@Override
	public DMLService dmlService() throws SQLException {
	  return new DMLServicePostgres(getDataSource());
	}
}
