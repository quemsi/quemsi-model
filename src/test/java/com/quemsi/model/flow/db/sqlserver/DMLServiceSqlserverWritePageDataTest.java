package com.quemsi.model.flow.db.sqlserver;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.microsoft.sqlserver.jdbc.Geography;
import com.microsoft.sqlserver.jdbc.ISQLServerPreparedStatement;
import com.quemsi.commons.util.BaseRuntimeException;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData.DataPage;

public class DMLServiceSqlserverWritePageDataTest {

	@Test
	public void writePageData_commitsBatch_andRestoresAutocommit() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		try (DMLServiceSqlserver dml = new DMLServiceSqlserver(dataSource(recording.connection), null)) {
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
		try (DMLServiceSqlserver dml = new DMLServiceSqlserver(dataSource(recording.connection), null)) {
			assertThrows(BaseRuntimeException.class, () ->
				dml.writePageData(tableWithIdName(), new DataPage(0, Map.of(1, new Object[] { 1, "a" }))));
		}

		assertThat(recording.rollbackCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(0));
		assertThat(recording.autoCommitAfterClose.get(), equalTo(true));
	}

	@Test
	public void quotedTable_bracketsSchemaAndSpacedName() {
		DbTable table = new DbTable("dbo", "Order Details");
		assertThat(DMLServiceSqlserver.quotedTable(table), equalTo("[dbo].[Order Details]"));
	}

	@Test
	public void writePageData_imageColumn_decodesBase64ToBytes() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		DbTable table = new DbTable("dbo", "pub_info");
		table.addColumn(DbColumn.builder().name("pub_id").dataType("char").columnType("char").ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("logo").dataType("image").columnType("image").ordinalPosition(2).nullable(true).build());

		String base64 = java.util.Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 });
		try (DMLServiceSqlserver dml = new DMLServiceSqlserver(dataSource(recording.connection), null)) {
			dml.writePageData(table, new DataPage(0, Map.of("0736", new Object[] { "0736", base64 })));
		}

		assertThat(recording.setBytesCalls.get(), equalTo(1));
		assertThat(recording.setStringCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(1));
	}

	@Test
	public void writePageData_ntextColumn_bindsAsNString_notBytes() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		DbTable table = new DbTable("dbo", "Suppliers");
		table.addColumn(DbColumn.builder().name("SupplierID").dataType("int").columnType("int").ordinalPosition(1).nullable(false).identity(true).build());
		table.addColumn(DbColumn.builder().name("CompanyName").dataType("nvarchar").columnType("nvarchar").ordinalPosition(2).nullable(false).build());
		table.addColumn(DbColumn.builder().name("HomePage").dataType("ntext").columnType("ntext").ordinalPosition(3).nullable(true).build());

		try (DMLServiceSqlserver dml = new DMLServiceSqlserver(dataSource(recording.connection), null)) {
			dml.writePageData(table, new DataPage(0, Map.of(1, new Object[] {
				1,
				"Exotic Liquids",
				Map.of("dataId", "HomePage", "data", "http://example.com")
			})));
		}

		assertThat(recording.setBytesCalls.get(), equalTo(0));
		assertThat(recording.setNStringCalls.get(), equalTo(2));
		assertThat(recording.setObjectCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(1));
	}

	@Test
	public void isBinaryColumnType_coversImageBinaryVarbinary() {
		assertThat(DMLServiceSqlserver.isBinaryColumnType("image"), equalTo(true));
		assertThat(DMLServiceSqlserver.isBinaryColumnType("varbinary"), equalTo(true));
		assertThat(DMLServiceSqlserver.isBinaryColumnType("binary"), equalTo(true));
		assertThat(DMLServiceSqlserver.isBinaryColumnType("varchar"), equalTo(false));
		assertThat(DMLServiceSqlserver.isBinaryColumnType("ntext"), equalTo(false));
	}

	@Test
	public void writePageData_geographyBase64_deserializesClrBytes() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		DbTable table = new DbTable("Person", "Address");
		table.addColumn(DbColumn.builder().name("AddressID").dataType("int").columnType("int").ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("SpatialLocation").dataType("geography").columnType("geography").ordinalPosition(2).nullable(true).build());

		/* CLR geography for POINT with SRID 4326 (AdventureWorks-style) */
		Geography sample = Geography.STGeomFromText("POINT(-122.34900 47.65100)", 4326);
		String base64 = java.util.Base64.getEncoder().encodeToString(sample.serialize());

		try (DMLServiceSqlserver dml = new DMLServiceSqlserver(dataSource(recording.connection), null)) {
			dml.writePageData(table, new DataPage(0, Map.of(1, new Object[] { 1, base64 })));
		}

		assertThat(recording.setGeographyCalls.get(), equalTo(1));
		assertThat(recording.setObjectCalls.get(), equalTo(1));
		assertThat(recording.commitCalls.get(), equalTo(1));
	}

	@Test
	public void extractSpatialBytes_decodesBase64Clr() throws Exception {
		Geography sample = Geography.STGeomFromText("POINT(-122.34900 47.65100)", 4326);
		byte[] clr = sample.serialize();
		String base64 = java.util.Base64.getEncoder().encodeToString(clr);
		assertThat(DMLServiceSqlserver.extractSpatialBytes(base64), equalTo(clr));
	}

	@Test
	public void isSpatialColumnType_coversGeographyAndGeometry() {
		assertThat(DMLServiceSqlserver.isSpatialColumnType("geography"), equalTo(true));
		assertThat(DMLServiceSqlserver.isSpatialColumnType("geometry"), equalTo(true));
		assertThat(DMLServiceSqlserver.isGeographyColumnType("geography"), equalTo(true));
		assertThat(DMLServiceSqlserver.isGeographyColumnType("geometry"), equalTo(false));
	}

	@Test
	public void parameterPlaceholder_hierarchyId_usesParse() {
		DbColumn orgNode = DbColumn.builder().name("OrganizationNode").dataType("hierarchyid").columnType("hierarchyid").ordinalPosition(1).build();
		assertThat(DMLServiceSqlserver.parameterPlaceholder(orgNode), equalTo("hierarchyid::Parse(?)"));
		assertThat(DMLServiceSqlserver.parameterPlaceholder(DbColumn.builder().name("id").dataType("int").columnType("int").ordinalPosition(1).build()), equalTo("?"));
	}

	@Test
	public void selectListForTable_hierarchyId_usesToString() {
		DbTable table = new DbTable("HumanResources", "Employee");
		table.addColumn(DbColumn.builder().name("BusinessEntityID").dataType("int").columnType("int").ordinalPosition(1).build());
		table.addColumn(DbColumn.builder().name("OrganizationNode").dataType("hierarchyid").columnType("hierarchyid").ordinalPosition(2).build());
		assertThat(DMLServiceSqlserver.selectListForTable(table),
			equalTo("t.[BusinessEntityID], t.[OrganizationNode].ToString() AS [OrganizationNode]"));
	}

	@Test
	public void looksLikeHierarchyPath_distinguishesPathFromBase64() {
		assertThat(DMLServiceSqlserver.looksLikeHierarchyPath("/"), equalTo(true));
		assertThat(DMLServiceSqlserver.looksLikeHierarchyPath("/1/2/"), equalTo(true));
		assertThat(DMLServiceSqlserver.looksLikeHierarchyPath("WA=="), equalTo(false));
	}

	@Test
	public void readHierarchyIdValue_prefersPathStringOverBytes() throws Exception {
		AtomicReference<String> stringVal = new AtomicReference<>("/1/2/");
		ResultSet rs = (ResultSet) Proxy.newProxyInstance(
			ResultSet.class.getClassLoader(),
			new Class<?>[] { ResultSet.class },
			(proxy, method, args) -> {
				String name = method.getName();
				if ("getString".equals(name)) {
					return stringVal.get();
				}
				if ("wasNull".equals(name)) {
					return stringVal.get() == null;
				}
				return defaultObjectMethod(proxy, method, args);
			});

		assertThat(DMLServiceSqlserver.readHierarchyIdValue(rs, "DocumentNode"), equalTo("/1/2/"));

		stringVal.set(null);
		assertThat(DMLServiceSqlserver.readHierarchyIdValue(rs, "DocumentNode"), equalTo(null));
	}

	@Test
	public void writePageData_hierarchyIdBase64_rejectsNonPath() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		DbTable table = new DbTable("HumanResources", "Employee");
		table.addColumn(DbColumn.builder().name("BusinessEntityID").dataType("int").columnType("int").ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("OrganizationNode").dataType("hierarchyid").columnType("hierarchyid").ordinalPosition(2).nullable(true).build());

		try (DMLServiceSqlserver dml = new DMLServiceSqlserver(dataSource(recording.connection), null)) {
			BaseRuntimeException ex = assertThrows(BaseRuntimeException.class, () ->
				dml.writePageData(table, new DataPage(0, Map.of(1, new Object[] { 1, "WA==" }))));
			assertThat(ex.getMessageId(), equalTo("unable-to-write-data"));
		}
	}

	@Test
	public void writePageData_dateColumn_bindsSqlDateNotBytes() throws Exception {
		RecordingConnection recording = new RecordingConnection(false);
		DbTable table = new DbTable("HumanResources", "EmployeeDepartmentHistory");
		table.addColumn(DbColumn.builder().name("BusinessEntityID").dataType("int").columnType("int").ordinalPosition(1).nullable(false).build());
		table.addColumn(DbColumn.builder().name("StartDate").dataType("date").columnType("date").ordinalPosition(2).nullable(false).build());

		try (DMLServiceSqlserver dml = new DMLServiceSqlserver(dataSource(recording.connection), null)) {
			dml.writePageData(table, new DataPage(0, Map.of(1, new Object[] { 1, "2003-01-14" })));
			dml.writePageData(table, new DataPage(0, Map.of(2, new Object[] { 2, "2009-01-14T00:00:00.000" })));
		}

		assertThat(recording.setDateCalls.get(), equalTo(2));
		assertThat(recording.setBytesCalls.get(), equalTo(0));
	}

	@Test
	public void toSqlDate_parsesIsoAndBackupFormats() {
		assertThat(DMLServiceSqlserver.toSqlDate("2003-01-14"), equalTo(java.sql.Date.valueOf("2003-01-14")));
		assertThat(DMLServiceSqlserver.toSqlDate("2009-01-14T00:00:00.000"), equalTo(java.sql.Date.valueOf("2009-01-14")));
	}

	private static DbTable tableWithIdName() {
		DbTable table = new DbTable("dbo", "offices");
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
		final AtomicInteger setBytesCalls = new AtomicInteger();
		final AtomicInteger setObjectCalls = new AtomicInteger();
		final AtomicInteger setStringCalls = new AtomicInteger();
		final AtomicInteger setNStringCalls = new AtomicInteger();
		final AtomicInteger setGeographyCalls = new AtomicInteger();
		final AtomicInteger setDateCalls = new AtomicInteger();
		final AtomicBoolean autoCommit = new AtomicBoolean(true);
		final AtomicBoolean autoCommitAfterClose = new AtomicBoolean();
		final boolean failOnExecuteBatch;
		volatile String lastPrepareSql;
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
					lastPrepareSql = (String) args[0];
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
				ISQLServerPreparedStatement.class.getClassLoader(),
				new Class<?>[] { ISQLServerPreparedStatement.class },
				(proxy, method, args) -> {
					String name = method.getName();
					if ("unwrap".equals(name)) {
						Class<?> iface = (Class<?>) args[0];
						if (iface.isInstance(proxy)) {
							return proxy;
						}
						throw new SQLException("cannot unwrap to " + iface.getName());
					}
					if ("isWrapperFor".equals(name)) {
						Class<?> iface = (Class<?>) args[0];
						return iface.isInstance(proxy);
					}
					if ("setBytes".equals(name)) {
						setBytesCalls.incrementAndGet();
						return null;
					}
					if ("setGeography".equals(name)) {
						setGeographyCalls.incrementAndGet();
						return null;
					}
					if ("setDate".equals(name)) {
						setDateCalls.incrementAndGet();
						return null;
					}
					if ("setTimestamp".equals(name) || "setTime".equals(name)) {
						return null;
					}
					if ("setGeometry".equals(name)) {
						return null;
					}
					if ("setNString".equals(name)) {
						setNStringCalls.incrementAndGet();
						return null;
					}
					if ("setString".equals(name)) {
						setStringCalls.incrementAndGet();
						return null;
					}
					if ("setObject".equals(name)) {
						setObjectCalls.incrementAndGet();
						return null;
					}
					if ("setNull".equals(name)) {
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
