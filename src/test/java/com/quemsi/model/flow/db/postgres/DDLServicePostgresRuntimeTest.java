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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
