package com.quemsi.model.flow.db.mysql;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbView;
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
  and not exists (
    select 1 from INFORMATION_SCHEMA.VIEWS v
    where v.TABLE_SCHEMA = cols.TABLE_SCHEMA and v.TABLE_NAME = cols.TABLE_NAME
  )
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
  and not exists (
    select 1 from INFORMATION_SCHEMA.VIEWS v
    where v.TABLE_SCHEMA = kcu.TABLE_SCHEMA and v.TABLE_NAME = kcu.TABLE_NAME
  )
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
  and not exists (
    select 1 from INFORMATION_SCHEMA.VIEWS v
    where v.TABLE_SCHEMA = st.TABLE_SCHEMA and v.TABLE_NAME = st.TABLE_NAME
  )
order by st.TABLE_NAME, st.INDEX_NAME, st.SEQ_IN_INDEX;
			""";

	private static final String SQL_FOR_CHECK_CONSTRAINTS = """
SELECT 
	tc.CONSTRAINT_SCHEMA as table_schema,
	tc.TABLE_NAME as table_name,
	cc.CONSTRAINT_NAME as constraint_name,
	cc.CHECK_CLAUSE as condef
FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
INNER JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc 
	ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA 
	AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
WHERE tc.CONSTRAINT_TYPE = 'CHECK' AND tc.CONSTRAINT_SCHEMA = ?
  and not exists (
    select 1 from INFORMATION_SCHEMA.VIEWS v
    where v.TABLE_SCHEMA = tc.TABLE_SCHEMA and v.TABLE_NAME = tc.TABLE_NAME
  )
;
			""";

	private static final String SQL_FOR_VIEWS = """
SELECT
	TABLE_SCHEMA as schema_name,
	TABLE_NAME as view_name,
	VIEW_DEFINITION as definition
FROM INFORMATION_SCHEMA.VIEWS
WHERE TABLE_SCHEMA = ?
ORDER BY TABLE_NAME
;
			""";

	private static final String SQL_FOR_VIEW_DEPS = """
SELECT
	vtu.VIEW_SCHEMA as view_schema,
	vtu.VIEW_NAME as view_name,
	vtu.TABLE_SCHEMA as dep_schema,
	vtu.TABLE_NAME as dep_name
FROM INFORMATION_SCHEMA.VIEW_TABLE_USAGE vtu
INNER JOIN INFORMATION_SCHEMA.VIEWS v
	ON v.TABLE_SCHEMA = vtu.TABLE_SCHEMA AND v.TABLE_NAME = vtu.TABLE_NAME
WHERE vtu.VIEW_SCHEMA = ?
;
			""";

	/** Matches SHOW CREATE VIEW output and captures the SELECT body after AS. */
	private static final Pattern CREATE_VIEW_AS = Pattern.compile(
		"(?is)^\\s*CREATE\\s+(?:ALGORITHM\\s*=\\s*\\S+\\s+)?(?:DEFINER\\s*=\\s*[^\\s]+\\s+)?(?:SQL\\s+SECURITY\\s+\\w+\\s+)?VIEW\\s+.+?\\s+AS\\s+(.*)$"
	);
	
	private String name;
	private String dbName;
	private Set<String> schemas;
	private String url;
	private String username;
	private String password;
	private boolean readOnly;
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
			PreparedStatement ckps = con.prepareStatement(SQL_FOR_CHECK_CONSTRAINTS);
			PreparedStatement vps = con.prepareStatement(SQL_FOR_VIEWS);
			PreparedStatement vdps = con.prepareStatement(SQL_FOR_VIEW_DEPS);
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
				String schemaName = null; 
				String tableName = irs.getString("TABLE_NAME");
				String indexName = irs.getString("INDEX_NAME");
				String columnName = irs.getString("COLUMN_NAME");
				boolean nonUnique = irs.getBoolean("NON_UNIQUE");
				String indexType = irs.getString("INDEX_TYPE");
				String fullIndexName = new StringBuilder(dbName).append(".").append(tableName).toString();
				if(cur == null || !fullIndexName.equals(cur.qualifiedTableName()) || !indexName.equals(cur.getIndexName())){
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
			ckps.setString(1, dbName);
			ResultSet ckrs = ckps.executeQuery();
			while (ckrs.next()) {
				String schemaName = null;
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
			Map<String, DbView> viewsByName = new HashMap<>();
			// Collect view names first, then load definitions on a fresh connection.
			// MySQL JDBC allows only one active ResultSet per connection; earlier
			// catalog queries leave ResultSets open on `con`, so SHOW CREATE VIEW
			// on that same connection fails and definitions become "".
			List<String> viewNames = new LinkedList<>();
			vps.setString(1, dbName);
			try (ResultSet vrs = vps.executeQuery()) {
				while (vrs.next()) {
					viewNames.add(vrs.getString("VIEW_NAME"));
				}
			}
			try (Connection viewCon = getDataSource().getConnection()) {
				for (String viewName : viewNames) {
					String definition = loadViewDefinition(viewCon, viewName);
					DbView view = DbView.builder()
						.schema(null)
						.name(viewName)
						.definition(definition != null ? definition : "")
						.dependsOnViews(new HashSet<>())
						.build();
					viewsByName.put(view.qualifiedName(), view);
					dbModel.getViews().add(view);
				}
			}
			vdps.setString(1, dbName);
			try {
				ResultSet vdrs = vdps.executeQuery();
				while (vdrs.next()) {
					String viewName = vdrs.getString("VIEW_NAME");
					String depName = vdrs.getString("DEP_NAME");
					DbView view = viewsByName.get(viewName);
					if (view != null && viewsByName.containsKey(depName)) {
						view.getDependsOnViews().add(depName);
					}
				}
			} catch (SQLException e) {
				log.warn("Unable to load MySQL view dependencies (VIEW_TABLE_USAGE may be unavailable): {}", e.getMessage());
			}
			dbModel.build();
		}catch(Exception e){
			throw Exceptions.server("unable-to-build-dbmodel").withCause(e).get();
		}
		return dbModel;
	}

	/**
	 * Prefer SHOW CREATE VIEW / SHOW CREATE TABLE. INFORMATION_SCHEMA.VIEW_DEFINITION
	 * is often empty for non-definer users.
	 */
	private String loadViewDefinition(Connection con, String viewName) {
		String catalog = resolveCatalog(con);
		List<String> candidates = new LinkedList<>();
		if (catalog != null && !catalog.isBlank()) {
			candidates.add(backtickQuoted(catalog) + "." + backtickQuoted(viewName));
		}
		candidates.add(backtickQuoted(viewName));

		for (String qualified : candidates) {
			String body = executeShowCreate(con, "SHOW CREATE VIEW " + qualified);
			if (body == null || body.isBlank()) {
				// SHOW CREATE TABLE also works for views in MySQL
				body = executeShowCreate(con, "SHOW CREATE TABLE " + qualified);
			}
			if (body != null && !body.isBlank()) {
				return body;
			}
		}

		String fromInfoSchema = loadViewDefinitionFromInformationSchema(con, catalog, viewName);
		if (fromInfoSchema != null && !fromInfoSchema.isBlank()) {
			return fromInfoSchema.trim();
		}
		log.warn("Unable to load MySQL view definition for {} (catalog={}). "
			+ "Grant SHOW VIEW on the schema to the backup user, e.g. GRANT SELECT, SHOW VIEW ON `{}`.* TO 'user'@'%'",
			viewName, catalog, catalog != null ? catalog : "<database>");
		return "";
	}

	private String resolveCatalog(Connection con) {
		if (dbName != null && !dbName.isBlank()) {
			return dbName;
		}
		try {
			return con.getCatalog();
		} catch (SQLException e) {
			return null;
		}
	}

	private String executeShowCreate(Connection con, String sql) {
		try (Statement st = con.createStatement();
			 ResultSet rs = st.executeQuery(sql)) {
			if (!rs.next()) {
				return null;
			}
			String createStmt = readCreateStatementColumn(rs);
			return stripCreateViewWrapper(createStmt);
		} catch (SQLException e) {
			// ER_TABLEACCESS_DENIED_ERROR (1142): typically missing SHOW VIEW privilege
			if (e.getErrorCode() == 1142 || (e.getMessage() != null && e.getMessage().toUpperCase().contains("SHOW VIEW"))) {
				log.warn("MySQL view DDL requires SHOW VIEW privilege. {} failed: {}", sql, e.getMessage());
			} else {
				log.debug("MySQL {} failed: {}", sql, e.getMessage());
			}
			return null;
		}
	}

	private static String readCreateStatementColumn(ResultSet rs) throws SQLException {
		var meta = rs.getMetaData();
		int count = meta.getColumnCount();
		for (int i = 1; i <= count; i++) {
			String label = meta.getColumnLabel(i);
			if (label == null) {
				label = meta.getColumnName(i);
			}
			if (label != null && label.toLowerCase().contains("create")) {
				return rs.getString(i);
			}
		}
		// SHOW CREATE VIEW/TABLE: column 2 is the create statement
		if (count >= 2) {
			return rs.getString(2);
		}
		return null;
	}

	private String loadViewDefinitionFromInformationSchema(Connection con, String catalog, String viewName) {
		if (catalog == null || catalog.isBlank()) {
			return null;
		}
		String sql = "SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
		try (PreparedStatement ps = con.prepareStatement(sql)) {
			ps.setString(1, catalog);
			ps.setString(2, viewName);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					return rs.getString(1);
				}
			}
		} catch (SQLException e) {
			log.debug("INFORMATION_SCHEMA.VIEWS lookup failed for {}: {}", viewName, e.getMessage());
		}
		return null;
	}

	static String stripCreateViewWrapper(String createView) {
		if (createView == null) {
			return null;
		}
		String trimmed = createView.trim();
		Matcher matcher = CREATE_VIEW_AS.matcher(trimmed);
		if (matcher.matches()) {
			return matcher.group(1).trim();
		}
		// Fallback: take everything after VIEW ... AS
		Matcher viewAs = Pattern.compile("(?is)\\bVIEW\\b.+?\\bAS\\b\\s+(.*)$").matcher(trimmed);
		if (viewAs.find()) {
			return viewAs.group(1).trim();
		}
		return trimmed;
	}

	private static String backtickQuoted(String name) {
		return "`" + name.replace("`", "``") + "`";
	}
}
