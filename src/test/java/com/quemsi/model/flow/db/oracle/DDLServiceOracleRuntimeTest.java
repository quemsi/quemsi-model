package com.quemsi.model.flow.db.oracle;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

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

import org.junit.jupiter.api.Test;

import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;

public class DDLServiceOracleRuntimeTest {

	@Test
	public void disableConstraints_batchesDropConstraint() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceOracle ddl = new DDLServiceOracle(recording.connection)) {
			ReferenceInfo ref = new ReferenceInfo(
				"FK_EMP_DEPT",
				"HR",
				"EMPLOYEES",
				new LinkedHashSet<>(List.of("DEPT_ID")),
				"HR",
				"DEPARTMENTS",
				new LinkedHashSet<>(List.of("ID"))
			);

			ddl.disableConstraints(Set.of(ref));
		}

		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.batchedSql, hasItem("ALTER TABLE HR.EMPLOYEES DROP CONSTRAINT \"FK_EMP_DEPT\""));
	}

	@Test
	public void enableContraints_batchesAddForeignKey() throws Exception {
		RecordingJdbc recording = new RecordingJdbc();
		try (DDLServiceOracle ddl = new DDLServiceOracle(recording.connection)) {
			ReferenceInfo ref = new ReferenceInfo(
				"FK_EMP_DEPT",
				"HR",
				"EMPLOYEES",
				new LinkedHashSet<>(List.of("DEPT_ID")),
				"HR",
				"DEPARTMENTS",
				new LinkedHashSet<>(List.of("ID"))
			);

			ddl.enableContraints(Set.of(ref));
		}

		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.batchedSql, hasSize(1));
		assertThat(recording.batchedSql.get(0), containsString("ALTER TABLE HR.EMPLOYEES ADD CONSTRAINT \"FK_EMP_DEPT\""));
		assertThat(recording.batchedSql.get(0), containsString("FOREIGN KEY"));
	}

	private static final class RecordingJdbc {
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
