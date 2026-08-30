package com.quemsi.model.flow.db.mysql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;

public class DMLServiceMysqlWritePageDataTest {

	@Test
	public void truncateTableSql_usesUnquotedName() {
		assertThat(DMLServiceMysql.truncateTableSql("offices"), equalTo("truncate table offices"));
	}

	@Test
	public void writePageData_disablesChecks_commitsBatch_andRestoresSession() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		try (DMLServiceMysql dml = new DMLServiceMysql(dataSource(recording.connection))) {
			DbTable table = tableWithIdName();
			DataPage page = new DataPage(0, Map.of(
				1, new Object[] { 1, "a" },
				2, new Object[] { 2, "b" }
			));

			dml.writePageData(table, page);
		}

		assertThat(recording.executedSql, contains(
			"SET FOREIGN_KEY_CHECKS=0, UNIQUE_CHECKS=0",
			"SET FOREIGN_KEY_CHECKS=1, UNIQUE_CHECKS=1"
		));
		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(1));
		assertThat(recording.rollbackCalls.get(), equalTo(0));
		assertThat(recording.autoCommitAfterClose.get(), equalTo(true));
		assertThat(recording.addBatchCalls.get(), equalTo(2));
	}

	@Test
	public void writePageData_convertsYearDateAndStringToInt() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		try (DMLServiceMysql dml = new DMLServiceMysql(dataSource(recording.connection))) {
			DbTable table = new DbTable(null, "film");
			table.addColumn(DbColumn.builder().name("film_id").dataType("smallint").columnType("smallint")
				.ordinalPosition(1).nullable(false).build());
			table.addColumn(DbColumn.builder().name("release_year").dataType("year").columnType("year")
				.ordinalPosition(2).nullable(true).build());

			dml.writePageData(table, new DataPage(0, Map.of(
				1, new Object[] { 1, java.sql.Date.valueOf("2006-01-01") },
				2, new Object[] { 2, "2007-01-01" }
			)));
		}

		assertThat(recording.setIntCalls.get(), equalTo(2));
		assertThat(recording.executeBatchCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(1));
	}

	@Test
	public void writePageData_onBatchFailure_rollsBackAndRestoresSession() throws Exception {
		RecordingConnection recording = new RecordingConnection(true);
		try (DMLServiceMysql dml = new DMLServiceMysql(dataSource(recording.connection))) {
			DbTable table = tableWithIdName();
			DataPage page = new DataPage(0, Map.of(1, new Object[] { 1, "a" }));

			assertThrows(BaseRuntimeException.class, () -> dml.writePageData(table, page));
		}

		assertThat(recording.executedSql, hasItem("SET FOREIGN_KEY_CHECKS=0, UNIQUE_CHECKS=0"));
		assertThat(recording.executedSql, hasItem("SET FOREIGN_KEY_CHECKS=1, UNIQUE_CHECKS=1"));
		assertThat(recording.rollbackCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(0));
		assertThat(recording.autoCommitAfterClose.get(), equalTo(true));
	}

	private static DbTable tableWithIdName() {
		DbTable table = new DbTable(null, "offices");
		table.addColumn(DbColumn.builder().name("id").dataType("int").columnType("int").ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("name").dataType("varchar").columnType("varchar(50)").ordinalPosition(2).nullable(true).build());
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
				if ("equals".equals(method.getName())) {
					return proxy == args[0];
				}
				if ("hashCode".equals(method.getName())) {
					return System.identityHashCode(proxy);
				}
				if ("toString".equals(method.getName())) {
					return "RecordingDataSource";
				}
				throw new UnsupportedOperationException(method.getName());
			});
	}

	private static final class RecordingConnection {
		final List<String> executedSql = new ArrayList<>();
		final AtomicInteger commitCalls = new AtomicInteger();
		final AtomicInteger rollbackCalls = new AtomicInteger();
		final AtomicInteger executeBatchCalls = new AtomicInteger();
		final AtomicInteger addBatchCalls = new AtomicInteger();
		final AtomicInteger setIntCalls = new AtomicInteger();
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
				case "createStatement":
					return statementProxy();
				case "prepareStatement":
					return preparedStatementProxy();
				case "close":
					autoCommitAfterClose.set(autoCommit.get());
					return null;
				case "isClosed":
					return false;
				case "equals":
					return proxy == args[0];
				case "hashCode":
					return System.identityHashCode(proxy);
				case "toString":
					return "RecordingConnection";
				default:
					throw new UnsupportedOperationException(name);
			}
		}

		private Statement statementProxy() {
			return (Statement) Proxy.newProxyInstance(
				Statement.class.getClassLoader(),
				new Class<?>[] { Statement.class },
				(proxy, method, args) -> {
					String name = method.getName();
					if ("execute".equals(name) && args != null && args.length == 1 && args[0] instanceof String sql) {
						executedSql.add(sql);
						return false;
					}
					if ("close".equals(name) || "isClosed".equals(name)) {
						return "isClosed".equals(name) ? false : null;
					}
					if ("equals".equals(name)) {
						return proxy == args[0];
					}
					if ("hashCode".equals(name)) {
						return System.identityHashCode(proxy);
					}
					if ("toString".equals(name)) {
						return "RecordingStatement";
					}
					throw new UnsupportedOperationException(name);
				});
		}

		private PreparedStatement preparedStatementProxy() {
			return (PreparedStatement) Proxy.newProxyInstance(
				PreparedStatement.class.getClassLoader(),
				new Class<?>[] { PreparedStatement.class },
				(proxy, method, args) -> {
					String name = method.getName();
					if ("setInt".equals(name)) {
						setIntCalls.incrementAndGet();
						return null;
					}
					if ("setObject".equals(name) || "setNull".equals(name) || "setBytes".equals(name)
						|| "setLong".equals(name) || "setShort".equals(name) || "setString".equals(name)) {
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
					if ("equals".equals(name)) {
						return proxy == args[0];
					}
					if ("hashCode".equals(name)) {
						return System.identityHashCode(proxy);
					}
					if ("toString".equals(name)) {
						return "RecordingPreparedStatement";
					}
					throw new UnsupportedOperationException(name);
				});
		}
	}
}
