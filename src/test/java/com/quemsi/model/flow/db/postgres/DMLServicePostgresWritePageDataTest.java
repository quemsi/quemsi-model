package com.quemsi.model.flow.db.postgres;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;

public class DMLServicePostgresWritePageDataTest {

	@Test
	public void writePageData_commitsBatch_andRestoresAutocommit() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		try (DMLServicePostgres dml = new DMLServicePostgres(dataSource(recording.connection))) {
			dml.writePageData(tableWithIdName(), new DataPage(0, Map.of(
				1, new Object[] { 1, "a" },
				2, new Object[] { 2, "b" }
			)));
		}

		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(1));
		assertThat(recording.rollbackCalls.get(), equalTo(0));
		assertThat(recording.autoCommitAfterClose.get(), equalTo(true));
		assertThat(recording.addBatchCalls.get(), equalTo(2));
	}

	@Test
	public void writePageData_onBatchFailure_rollsBackAndRestoresAutocommit() throws Exception {
		RecordingConnection recording = new RecordingConnection(true);
		try (DMLServicePostgres dml = new DMLServicePostgres(dataSource(recording.connection))) {
			assertThrows(BaseRuntimeException.class, () ->
				dml.writePageData(tableWithIdName(), new DataPage(0, Map.of(1, new Object[] { 1, "a" }))));
		}

		assertThat(recording.rollbackCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(0));
		assertThat(recording.autoCommitAfterClose.get(), equalTo(true));
	}

	private static DbTable tableWithIdName() {
		DbTable table = new DbTable("public", "offices");
		table.addColumn(DbColumn.builder().name("id").dataType("int").columnType("int").ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("name").dataType("varchar").columnType("varchar").ordinalPosition(2).nullable(true).build());
		return table;
	}

	private static DataSource dataSource(Connection connection) {
		return (DataSource) Proxy.newProxyInstance(
			DataSource.class.getClassLoader(),
			new Class<?>[] { DataSource.class },
			(proxy, method, args) -> {
				if ("getConnection".equals(method.getName()) && (args == null || args.length == 0)) {
					return connection;
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
			return "RecordingProxy";
		}
		throw new UnsupportedOperationException(name);
	}

	private static final class RecordingConnection {
		final AtomicInteger commitCalls = new AtomicInteger();
		final AtomicInteger rollbackCalls = new AtomicInteger();
		final AtomicInteger executeBatchCalls = new AtomicInteger();
		final AtomicInteger addBatchCalls = new AtomicInteger();
		final AtomicBoolean autoCommit = new AtomicBoolean(true);
		final AtomicBoolean autoCommitAfterClose = new AtomicBoolean();
		final boolean failOnExecuteBatch;
		final Connection connection;

		RecordingConnection(boolean failOnExecuteBatch) {
			this.failOnExecuteBatch = failOnExecuteBatch;
			this.connection = (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class },
				this::invokeConnection);
		}

		private Object invokeConnection(Object proxy, Method method, Object[] args) throws SQLException {
			String name = method.getName();
			switch (name) {
				case "getAutoCommit":
					return autoCommit.get();
				case "setAutoCommit":
					autoCommit.set((Boolean) args[0]);
					return null;
				case "commit":
					commitCalls.incrementAndGet();
					return null;
				case "rollback":
					if (args == null || args.length == 0) {
						rollbackCalls.incrementAndGet();
					}
					return null;
				case "prepareStatement":
					return preparedStatementProxy();
				case "close":
					autoCommitAfterClose.set(autoCommit.get());
					return null;
				case "isClosed":
					return false;
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
					if ("setObject".equals(name) || "setNull".equals(name) || "setString".equals(name)
						|| "setBytes".equals(name) || "setArray".equals(name)) {
						return null;
					}
					if ("addBatch".equals(name) && (args == null || args.length == 0)) {
						addBatchCalls.incrementAndGet();
						return null;
					}
					if ("executeBatch".equals(name)) {
						executeBatchCalls.incrementAndGet();
						if (failOnExecuteBatch) {
							throw new SQLException("batch failed");
						}
						return new int[] { 1, 1 };
					}
					if ("close".equals(name) || "isClosed".equals(name)) {
						return "isClosed".equals(name) ? false : null;
					}
					return defaultObjectMethod(proxy, method, args);
				});
		}
	}
}
