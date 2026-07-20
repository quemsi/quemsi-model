package com.quemsi.model.flow.db.mysql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;

public class DDLServiceMysqlRuntimeTest {

	@Test
	public void buildMultiTableDropSql_joinsNames() {
		assertThat(DDLServiceMysql.buildMultiTableDropSql("a", "b", "c"),
			equalTo("DROP TABLE IF EXISTS a, b, c"));
		assertThat(DDLServiceMysql.buildMultiTableDropSql(), nullValue());
		assertThat(DDLServiceMysql.buildMultiTableDropSql((String[]) null), nullValue());
	}

	@Test
	public void stripTrailingSemicolon_removesTrailingSemicolons() {
		assertThat(DDLServiceMysql.stripTrailingSemicolon("DROP TABLE t;"), equalTo("DROP TABLE t"));
		assertThat(DDLServiceMysql.stripTrailingSemicolon("CREATE TABLE t ();"), equalTo("CREATE TABLE t ()"));
		assertThat(DDLServiceMysql.stripTrailingSemicolon("x;;"), equalTo("x"));
	}

	@Test
	public void formatColumnDefaultValue_quotesStringLikeAndEmptyDefaults() {
		assertThat(DDLServiceMysql.formatColumnDefaultValue("char(52)", ""), equalTo("''"));
		assertThat(DDLServiceMysql.formatColumnDefaultValue("enum('Asia','Europe')", "Asia"), equalTo("'Asia'"));
		assertThat(DDLServiceMysql.formatColumnDefaultValue("varchar(50)", "O'Brien"), equalTo("'O''Brien'"));
		assertThat(DDLServiceMysql.formatColumnDefaultValue("int", "0"), equalTo("0"));
		assertThat(DDLServiceMysql.formatColumnDefaultValue("timestamp", "CURRENT_TIMESTAMP"), equalTo("CURRENT_TIMESTAMP"));
		assertThat(DDLServiceMysql.formatColumnDefaultValue("datetime", "2020-01-01 00:00:00"), equalTo("'2020-01-01 00:00:00'"));
	}

	@Test
	public void createTables_quotesEmptyCharAndEnumDefaults() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceMysql ddl = new DDLServiceMysql(recording.dataSource)) {
			DbModel dbModel = new DbModel();
			DbTable country = dbModel.addTable("country");
			country.addColumn(DbColumn.builder().name("Code").dataType("char").columnType("char(3)")
				.ordinalPosition(1).nullable(false).build());
			country.addColumn(DbColumn.builder().name("Name").dataType("char").columnType("char(52)")
				.ordinalPosition(2).nullable(false).columnDefault("").build());
			country.addColumn(DbColumn.builder().name("Continent").dataType("enum")
				.columnType("enum('Asia','Europe','North America','Africa','Oceania','Antarctica','South America')")
				.ordinalPosition(3).nullable(false).columnDefault("Asia").build());
			country.getPkColumnNames().add("Code");

			ddl.createTables(dbModel);
		}

		assertThat(recording.batchedSql, hasSize(1));
		assertThat(recording.batchedSql.get(0), containsString("`Name` char(52) NOT NULL DEFAULT ''"));
		assertThat(recording.batchedSql.get(0), containsString("`Continent` enum('Asia','Europe','North America','Africa','Oceania','Antarctica','South America') NOT NULL DEFAULT 'Asia'"));
	}

	@Test
	public void createTables_emitsFulltextKeyAndPrefixLengths() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceMysql ddl = new DDLServiceMysql(recording.dataSource)) {
			DbModel dbModel = new DbModel();
			DbTable filmText = dbModel.addTable("film_text");
			filmText.addColumn(DbColumn.builder().name("film_id").dataType("smallint").columnType("smallint")
				.ordinalPosition(1).nullable(false).build());
			filmText.addColumn(DbColumn.builder().name("title").dataType("varchar").columnType("varchar(255)")
				.ordinalPosition(2).nullable(false).build());
			filmText.addColumn(DbColumn.builder().name("description").dataType("text").columnType("text")
				.ordinalPosition(3).nullable(true).build());
			filmText.getPkColumnNames().add("film_id");

			IndexInfo fulltext = new IndexInfo(null, "film_text", "idx_title_description", false, "FULLTEXT");
			fulltext.getColumns().add("title");
			fulltext.getColumns().add("description");
			fulltext.getColumnPrefixLengths().add(null);
			fulltext.getColumnPrefixLengths().add(null);
			dbModel.getIndexes().computeIfAbsent("film_text", k -> new java.util.HashMap<>())
				.put(fulltext.getIndexName(), fulltext);

			IndexInfo prefixIdx = new IndexInfo(null, "film_text", "idx_description_prefix", false, "BTREE");
			prefixIdx.getColumns().add("description");
			prefixIdx.getColumnPrefixLengths().add(100);
			dbModel.getIndexes().get("film_text").put(prefixIdx.getIndexName(), prefixIdx);

			ddl.createTables(dbModel);
		}

		assertThat(recording.batchedSql, hasSize(1));
		assertThat(recording.batchedSql.get(0), containsString("FULLTEXT KEY idx_title_description (`title`, `description`)"));
		assertThat(recording.batchedSql.get(0), containsString("KEY idx_description_prefix (`description`(100))"));
	}

	@Test
	public void createTables_resolvesIndexWhenMapKeyedByBareTableName() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceMysql ddl = new DDLServiceMysql(recording.dataSource)) {
			DbModel dbModel = new DbModel();
			DbTable table = dbModel.addTable("department_manager", "employees");
			table.addColumn(DbColumn.builder().name("department_id").dataType("char").columnType("char(4)")
				.ordinalPosition(1).nullable(false).build());
			table.getPkColumnNames().add("department_id");

			IndexInfo index = new IndexInfo("employees", "department_manager", "idx_16985_dept_no", false, "BTREE");
			index.getColumns().add("department_id");
			index.getColumnPrefixLengths().add(null);
			// Legacy / mismatched backup key: bare table name while table is schema-qualified
			dbModel.getIndexes().computeIfAbsent("department_manager", k -> new java.util.HashMap<>())
				.put(index.getIndexName(), index);

			ddl.createTables(dbModel);
		}

		assertThat(recording.batchedSql, hasSize(1));
		assertThat(recording.batchedSql.get(0), containsString("KEY idx_16985_dept_no (`department_id`)"));
	}

	@Test
	public void mysqlInlineIndexKeyword_handlesFulltextSpatialUnique() {
		assertThat(DDLServiceMysql.mysqlInlineIndexKeyword(new IndexInfo(null, "t", "i", false, "FULLTEXT")),
			equalTo("FULLTEXT KEY"));
		assertThat(DDLServiceMysql.mysqlInlineIndexKeyword(new IndexInfo(null, "t", "i", false, "SPATIAL")),
			equalTo("SPATIAL KEY"));
		assertThat(DDLServiceMysql.mysqlInlineIndexKeyword(new IndexInfo(null, "t", "i", true, "BTREE")),
			equalTo("UNIQUE KEY"));
		assertThat(DDLServiceMysql.mysqlInlineIndexKeyword(new IndexInfo(null, "t", "i", false, "BTREE")),
			equalTo("KEY"));
	}

	@Test
	public void createTables_spatialIndex_ignoresSubPartPrefix() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceMysql ddl = new DDLServiceMysql(recording.dataSource)) {
			DbModel dbModel = new DbModel();
			DbTable address = dbModel.addTable("address");
			address.addColumn(DbColumn.builder().name("address_id").dataType("smallint").columnType("smallint")
				.ordinalPosition(1).nullable(false).build());
			address.addColumn(DbColumn.builder().name("location").dataType("geometry").columnType("geometry")
				.ordinalPosition(2).nullable(false).build());
			address.getPkColumnNames().add("address_id");

			IndexInfo spatial = new IndexInfo(null, "address", "idx_location", false, "SPATIAL");
			spatial.getColumns().add("location");
			spatial.getColumnPrefixLengths().add(32);
			dbModel.getIndexes().computeIfAbsent("address", k -> new java.util.HashMap<>())
				.put(spatial.getIndexName(), spatial);

			ddl.createTables(dbModel);
		}

		assertThat(recording.batchedSql, hasSize(1));
		assertThat(recording.batchedSql.get(0), containsString("SPATIAL KEY idx_location (`location`)"));
		assertThat(recording.batchedSql.get(0), not(containsString("`location`(32)")));
	}

	@Test
	public void dropTables_disablesFkChecks_andRunsMultiTableDrop() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceMysql ddl = new DDLServiceMysql(recording.dataSource)) {
			ddl.dropTables("orderdetails", "orders", "customers");
		}

		assertThat(recording.executedSql, contains(
			"SET FOREIGN_KEY_CHECKS=0",
			"DROP TABLE IF EXISTS orderdetails, orders, customers",
			"SET FOREIGN_KEY_CHECKS=1"
		));
		assertThat(recording.executeBatchCalls.get(), equalTo(0));
	}

	@Test
	public void createTables_batchesScripts_andOmitsAllForeignKeys() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceMysql ddl = new DDLServiceMysql(recording.dataSource)) {
			DbModel dbModel = new DbModel();
			DbTable offices = dbModel.addTable("offices");
			offices.addColumn(DbColumn.builder().name("officeCode").dataType("varchar").columnType("varchar(10)")
				.ordinalPosition(1).nullable(false).build());
			offices.getPkColumnNames().add("officeCode");

			DbTable employees = dbModel.addTable("employees");
			employees.addColumn(DbColumn.builder().name("employeeNumber").dataType("int").columnType("int")
				.ordinalPosition(1).nullable(false).build());
			employees.addColumn(DbColumn.builder().name("officeCode").dataType("varchar").columnType("varchar(10)")
				.ordinalPosition(2).nullable(false).build());
			employees.addColumn(DbColumn.builder().name("reportsTo").dataType("int").columnType("int")
				.ordinalPosition(3).nullable(true).build());
			employees.getPkColumnNames().add("employeeNumber");

			ReferenceInfo officeFk = new ReferenceInfo(
				"employees_ibfk_2",
				null,
				"employees",
				new LinkedHashSet<>(List.of("officeCode")),
				null,
				"offices",
				new LinkedHashSet<>(List.of("officeCode"))
			);
			ReferenceInfo circular = new ReferenceInfo(
				"employees_ibfk_1",
				null,
				"employees",
				new LinkedHashSet<>(List.of("reportsTo")),
				null,
				"employees",
				new LinkedHashSet<>(List.of("employeeNumber"))
			);
			dbModel.getReferenceInfos().add(officeFk);
			dbModel.getReferenceInfos().add(circular);
			dbModel.getCircularIgnore().add(circular);

			ddl.createTables(dbModel);
		}

		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.batchedSql, hasSize(2));
		assertThat(recording.batchedSql.get(0), containsString("CREATE TABLE IF NOT EXISTS offices"));
		assertThat(recording.batchedSql.get(1), containsString("CREATE TABLE IF NOT EXISTS employees"));
		for (String sql : recording.batchedSql) {
			assertThat(sql, not(containsString("FOREIGN KEY")));
			assertThat(sql, not(containsString("employees_ibfk_1")));
			assertThat(sql, not(containsString("employees_ibfk_2")));
			assertThat(sql.endsWith(";"), equalTo(false));
		}
	}

	@Test
	public void disableConstraints_batchesOnSingleConnection() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceMysql ddl = new DDLServiceMysql(recording.dataSource)) {
			ReferenceInfo ref = new ReferenceInfo(
				"orders_ibfk_1",
				null,
				"orders",
				new LinkedHashSet<>(List.of("customerNumber")),
				null,
				"customers",
				new LinkedHashSet<>(List.of("customerNumber"))
			);

			ddl.disableConstraints(Set.of(ref));
		}

		assertThat(recording.getConnectionCalls.get(), equalTo(1));
		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.batchedSql, hasItem("ALTER TABLE orders DROP FOREIGN KEY `orders_ibfk_1`"));
	}

	@Test
	public void enableContraints_disablesFkChecks_andBatchesAddFk() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceMysql ddl = new DDLServiceMysql(recording.dataSource)) {
			ReferenceInfo ref = new ReferenceInfo(
				"employees_ibfk_1",
				null,
				"employees",
				new LinkedHashSet<>(List.of("reportsTo")),
				null,
				"employees",
				new LinkedHashSet<>(List.of("employeeNumber"))
			);

			ddl.enableContraints(Set.of(ref));
		}

		assertThat(recording.getConnectionCalls.get(), equalTo(1));
		assertThat(recording.executedSql, contains(
			"SET FOREIGN_KEY_CHECKS=0",
			"SET FOREIGN_KEY_CHECKS=1"
		));
		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.batchedSql, hasSize(1));
		assertThat(recording.batchedSql.get(0), containsString("ADD CONSTRAINT `employees_ibfk_1`"));
		assertThat(recording.batchedSql.get(0).endsWith(";"), equalTo(false));
	}

	private static final class RecordingJdbc {
		final List<String> executedSql = new ArrayList<>();
		final List<String> batchedSql = new ArrayList<>();
		final AtomicInteger executeBatchCalls = new AtomicInteger();
		final AtomicInteger getConnectionCalls = new AtomicInteger();
		final DataSource dataSource;
		final Connection connection;

		RecordingJdbc() {
			this.connection = (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class },
				this::invokeConnection);
			this.dataSource = (DataSource) Proxy.newProxyInstance(
				DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class },
				(proxy, method, args) -> {
					if ("getConnection".equals(method.getName()) && (args == null || args.length == 0)) {
						getConnectionCalls.incrementAndGet();
						return connection;
					}
					return defaultObjectMethod(proxy, method, args);
				});
		}

		private Object invokeConnection(Object proxy, Method method, Object[] args) throws SQLException {
			String name = method.getName();
			switch (name) {
				case "createStatement":
					return statementProxy();
				case "close":
				case "isClosed":
					return "isClosed".equals(name) ? false : null;
				default:
					return defaultObjectMethod(proxy, method, args);
			}
		}

		private Statement statementProxy() {
			return (Statement) Proxy.newProxyInstance(
				Statement.class.getClassLoader(),
				new Class<?>[] { Statement.class },
				(proxy, method, args) -> {
					String name = method.getName();
					if (("execute".equals(name) || "executeUpdate".equals(name))
						&& args != null && args.length == 1 && args[0] instanceof String sql) {
						executedSql.add(sql);
						return "execute".equals(name) ? false : 0;
					}
					if ("addBatch".equals(name) && args != null && args.length == 1 && args[0] instanceof String sql) {
						batchedSql.add(sql);
						return null;
					}
					if ("executeBatch".equals(name)) {
						executeBatchCalls.incrementAndGet();
						return new int[batchedSql.size()];
					}
					if ("close".equals(name) || "isClosed".equals(name)) {
						return "isClosed".equals(name) ? false : null;
					}
					return defaultObjectMethod(proxy, method, args);
				});
		}

		private static Object defaultObjectMethod(Object proxy, Method method, Object[] args) {
			String name = method.getName();
			if ("equals".equals(name)) {
				return proxy == args[0];
			}
			if ("hashCode".equals(name)) {
				return System.identityHashCode(proxy);
			}
			if ("toString".equals(name)) {
				return "RecordingJdbcProxy";
			}
			throw new UnsupportedOperationException(name);
		}
	}
}
