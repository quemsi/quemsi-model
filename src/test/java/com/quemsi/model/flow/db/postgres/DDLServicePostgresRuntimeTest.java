package com.quemsi.model.flow.db.postgres;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbDomainType;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbTrigger;

public class DDLServicePostgresRuntimeTest {

	@Test
	public void createEnumTypeSql_quotesSchemaAndEscapesLabels() {
		DbEnumType rating = DbEnumType.builder()
			.schema("public")
			.name("mpaa_rating")
			.labels(List.of("G", "PG", "PG-13", "R", "NC-17"))
			.build();
		assertThat(DDLServicePostgres.createEnumTypeSql(rating),
			equalTo("CREATE TYPE \"public\".\"mpaa_rating\" AS ENUM ('G', 'PG', 'PG-13', 'R', 'NC-17')"));
	}

	@Test
	public void createDomainTypeSql_includesBaseTypeAndCheck() {
		DbDomainType year = DbDomainType.builder()
			.schema("public")
			.name("year")
			.baseType("integer")
			.notNull(false)
			.checkConstraintName("year_check")
			.checkConstraintDef("CHECK (VALUE >= 1901 AND VALUE <= 2155)")
			.build();
		assertThat(DDLServicePostgres.createDomainTypeSql(year),
			equalTo("CREATE DOMAIN \"public\".\"year\" AS integer CONSTRAINT \"year_check\" CHECK (VALUE >= 1901 AND VALUE <= 2155)"));
	}

	@Test
	public void dropTriggerSql_quotesTableAndTrigger() {
		DbTrigger trigger = DbTrigger.builder()
			.schema("public")
			.tableName("film")
			.name("last_updated")
			.build();
		assertThat(DDLServicePostgres.dropTriggerSql(trigger),
			equalTo("DROP TRIGGER IF EXISTS \"last_updated\" ON \"public\".\"film\""));
	}

	@Test
	public void buildMultiTableDropSql_joinsNamesWithCascade() {
		assertThat(DDLServicePostgres.buildMultiTableDropSql("a", "b", "c"),
			equalTo("DROP TABLE IF EXISTS \"a\", \"b\", \"c\" CASCADE"));
		assertThat(DDLServicePostgres.buildMultiTableDropSql(), nullValue());
		assertThat(DDLServicePostgres.buildMultiTableDropSql((String[]) null), nullValue());
	}

	@Test
	public void dropTables_runsMultiTableCascadeDrop() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServicePostgres ddl = new DDLServicePostgres(recording.connection)) {
			ddl.dropTables("public.orderdetails", "public.orders", "public.customers");
		}

		assertThat(recording.executedSql, hasItem(
			"DROP TABLE IF EXISTS \"public\".\"orderdetails\", \"public\".\"orders\", \"public\".\"customers\" CASCADE"));
	}

	@Test
	public void createTables_batchesScripts_andOmitsAllForeignKeys() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServicePostgres ddl = new DDLServicePostgres(recording.connection)) {
			DbModel dbModel = new DbModel();
			dbModel.setSchemas(new HashSet<>());
			DbTable offices = dbModel.addTable("public.offices");
			offices.addColumn(DbColumn.builder().name("officeCode").dataType("varchar").columnType("varchar")
				.ordinalPosition(1).nullable(false).build());
			offices.getPkColumnNames().add("officeCode");
			offices.setPkConstraintName("offices_pkey");

			DbTable employees = dbModel.addTable("public.employees");
			employees.addColumn(DbColumn.builder().name("employeeNumber").dataType("int").columnType("int")
				.ordinalPosition(1).nullable(false).build());
			employees.addColumn(DbColumn.builder().name("officeCode").dataType("varchar").columnType("varchar")
				.ordinalPosition(2).nullable(false).build());
			employees.getPkColumnNames().add("employeeNumber");
			employees.setPkConstraintName("employees_pkey");

			ReferenceInfo officeFk = new ReferenceInfo(
				"employees_office_fk",
				"public",
				"employees",
				new LinkedHashSet<>(List.of("officeCode")),
				"public",
				"offices",
				new LinkedHashSet<>(List.of("officeCode"))
			);
			dbModel.getReferenceInfos().add(officeFk);

			ddl.createTables(dbModel);
		}

		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.batchedSql, hasSize(2));
		for (String sql : recording.batchedSql) {
			assertThat(sql, not(containsString("FOREIGN KEY")));
			assertThat(sql.endsWith(";"), equalTo(false));
		}
	}

	@Test
	public void createTables_resolvesIndexWhenMapKeyedByBareTableName() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServicePostgres ddl = new DDLServicePostgres(recording.connection)) {
			DbModel dbModel = new DbModel();
			dbModel.setSchemas(new HashSet<>());
			DbTable table = dbModel.addTable("department_manager", "employees");
			table.addColumn(DbColumn.builder().name("department_id").dataType("bpchar").columnType("bpchar")
				.ordinalPosition(1).nullable(false).build());
			table.getPkColumnNames().add("department_id");
			table.setPkConstraintName("department_manager_pkey");

			IndexInfo index = new IndexInfo("employees", "department_manager", "idx_16985_dept_no", false, "btree");
			index.getColumns().add("department_id");
			dbModel.getIndexes().computeIfAbsent("department_manager", k -> new HashMap<>())
				.put(index.getIndexName(), index);

			ddl.createTables(dbModel);
		}

		assertThat(recording.batchedSql.stream().anyMatch(s -> s.contains("idx_16985_dept_no")), equalTo(true));
		assertThat(recording.batchedSql.stream().anyMatch(s ->
			s.contains("CREATE INDEX IF NOT EXISTS \"idx_16985_dept_no\"")
				&& s.contains("\"employees\".\"department_manager\"")), equalTo(true));
	}

	@Test
	public void uniqueConstraintNamesForTable_collectsNames() {
		DbModel model = new DbModel();
		model.getContraintInfos().add(ContraintInfo.builder()
			.schema("bookings")
			.tableName("boarding_passes")
			.constraintName("boarding_passes_flight_id_boarding_no_key")
			.columnName("flight_id")
			.build());
		model.getContraintInfos().add(ContraintInfo.builder()
			.schema("bookings")
			.tableName("other")
			.constraintName("other_key")
			.columnName("x")
			.build());
		assertThat(DDLServicePostgres.uniqueConstraintNamesForTable(model, "bookings.boarding_passes"),
			equalTo(Set.of("boarding_passes_flight_id_boarding_no_key")));
	}

	@Test
	public void createTables_skipsUniqueConstraintBackingIndex() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServicePostgres ddl = new DDLServicePostgres(recording.connection)) {
			DbModel dbModel = new DbModel();
			dbModel.setSchemas(new HashSet<>());
			DbTable table = dbModel.addTable("boarding_passes", "bookings");
			table.addColumn(DbColumn.builder().name("flight_id").dataType("int").columnType("int")
				.ordinalPosition(1).nullable(false).build());
			table.addColumn(DbColumn.builder().name("boarding_no").dataType("int").columnType("int")
				.ordinalPosition(2).nullable(false).build());
			table.getPkColumnNames().add("flight_id");
			table.setPkConstraintName("boarding_passes_pkey");

			IndexInfo uniqueIndex = new IndexInfo("bookings", "boarding_passes",
				"boarding_passes_flight_id_boarding_no_key", true, "btree");
			uniqueIndex.getColumns().add("flight_id");
			uniqueIndex.getColumns().add("boarding_no");
			dbModel.getIndexes().computeIfAbsent("bookings.boarding_passes", k -> new HashMap<>())
				.put(uniqueIndex.getIndexName(), uniqueIndex);

			dbModel.getContraintInfos().add(ContraintInfo.builder()
				.schema("bookings")
				.tableName("boarding_passes")
				.constraintName("boarding_passes_flight_id_boarding_no_key")
				.columnName("flight_id")
				.columnName("boarding_no")
				.build());

			ddl.createTables(dbModel);
		}

		assertThat(recording.batchedSql.stream().anyMatch(s ->
			s.contains("ADD CONSTRAINT \"boarding_passes_flight_id_boarding_no_key\"")
				&& s.contains("UNIQUE")), equalTo(true));
		assertThat(recording.batchedSql.stream().anyMatch(s ->
			s.contains("CREATE UNIQUE INDEX")
				&& s.contains("boarding_passes_flight_id_boarding_no_key")), equalTo(false));
	}

	@Test
	public void createTables_skipsExistingTableAndItsUniqueConstraint() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		recording.existingTables.add(new String[] { "bookings", "boarding_passes" });
		try (DDLServicePostgres ddl = new DDLServicePostgres(recording.connection)) {
			DbModel dbModel = new DbModel();
			dbModel.setSchemas(Set.of("bookings"));
			DbTable table = dbModel.addTable("boarding_passes", "bookings");
			table.addColumn(DbColumn.builder().name("flight_id").dataType("int").columnType("int")
				.ordinalPosition(1).nullable(false).build());
			table.getPkColumnNames().add("flight_id");
			table.setPkConstraintName("boarding_passes_pkey");
			dbModel.getContraintInfos().add(ContraintInfo.builder()
				.schema("bookings")
				.tableName("boarding_passes")
				.constraintName("boarding_passes_flight_id_boarding_no_key")
				.columnName("flight_id")
				.build());

			ddl.createTables(dbModel);
		}

		assertThat(recording.batchedSql.stream().anyMatch(s -> s.contains("CREATE TABLE")), equalTo(false));
		assertThat(recording.batchedSql.stream().anyMatch(s ->
			s.contains("boarding_passes_flight_id_boarding_no_key")), equalTo(false));
	}

	@Test
	public void disableConstraints_batchesDropConstraint() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServicePostgres ddl = new DDLServicePostgres(recording.connection)) {
			ReferenceInfo ref = new ReferenceInfo(
				"orders_customer_fk",
				"public",
				"orders",
				new LinkedHashSet<>(List.of("customerNumber")),
				"public",
				"customers",
				new LinkedHashSet<>(List.of("customerNumber"))
			);

			ddl.disableConstraints(Set.of(ref));
		}

		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.batchedSql, hasItem(
			"ALTER TABLE \"public\".\"orders\" DROP CONSTRAINT IF EXISTS \"orders_customer_fk\""));
	}

	@Test
	public void enableContraints_batchesAddForeignKey() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServicePostgres ddl = new DDLServicePostgres(recording.connection)) {
			ReferenceInfo ref = new ReferenceInfo(
				"employees_office_fk",
				"public",
				"employees",
				new LinkedHashSet<>(List.of("officeCode")),
				"public",
				"offices",
				new LinkedHashSet<>(List.of("officeCode"))
			);

			ddl.enableContraints(Set.of(ref));
		}

		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.batchedSql, hasSize(1));
		assertThat(recording.batchedSql.get(0), containsString("ADD CONSTRAINT \"employees_office_fk\""));
		assertThat(recording.batchedSql.get(0), containsString("FOREIGN KEY"));
		assertThat(recording.batchedSql.get(0).endsWith(";"), equalTo(false));
	}

	private static final class RecordingJdbc {
		final List<String> executedSql = new ArrayList<>();
		final List<String> batchedSql = new ArrayList<>();
		final List<String[]> existingTables = new ArrayList<>();
		final AtomicInteger executeBatchCalls = new AtomicInteger();
		final Connection connection;

		RecordingJdbc() {
			this.connection = (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class },
				this::invokeConnection);
		}

		private Object invokeConnection(Object proxy, Method method, Object[] args) throws SQLException {
			String name = method.getName();
			switch (name) {
				case "createStatement":
					return statementProxy();
				case "prepareStatement":
					return preparedStatementProxy();
				case "close":
				case "isClosed":
					return "isClosed".equals(name) ? false : null;
				default:
					return defaultObjectMethod(proxy, method, args);
			}
		}

		private PreparedStatement preparedStatementProxy() {
			return (PreparedStatement) Proxy.newProxyInstance(
				PreparedStatement.class.getClassLoader(),
				new Class<?>[] { PreparedStatement.class },
				(proxy, method, args) -> {
					String name = method.getName();
					if ("executeQuery".equals(name)) {
						return existingTablesResultSet();
					}
					if (name.startsWith("set") || "close".equals(name) || "isClosed".equals(name)) {
						return "isClosed".equals(name) ? false : null;
					}
					return defaultObjectMethod(proxy, method, args);
				});
		}

		private ResultSet existingTablesResultSet() {
			AtomicInteger row = new AtomicInteger(-1);
			return (ResultSet) Proxy.newProxyInstance(
				ResultSet.class.getClassLoader(),
				new Class<?>[] { ResultSet.class },
				(proxy, method, args) -> {
					String name = method.getName();
					if ("next".equals(name)) {
						return row.incrementAndGet() < existingTables.size();
					}
					if ("getString".equals(name) && args != null && args[0] instanceof Integer col) {
						int i = row.get();
						return existingTables.get(i)[col - 1];
					}
					if ("close".equals(name) || "isClosed".equals(name)) {
						return "isClosed".equals(name) ? false : null;
					}
					return defaultObjectMethod(proxy, method, args);
				});
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
