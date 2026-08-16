package com.quemsi.model.flow.db.sqlserver;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.microsoft.sqlserver.jdbc.Geography;
import com.microsoft.sqlserver.jdbc.Geometry;
import com.microsoft.sqlserver.jdbc.ISQLServerConnection;
import com.microsoft.sqlserver.jdbc.ISQLServerPreparedStatement;
import com.microsoft.sqlserver.jdbc.SQLServerResultSet;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.CustomSerializedColumn;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.flow.subset.SqlSubsetSupport;
import com.quemsi.model.flow.subset.SqlSubsetSupport.LimitStyle;
import com.quemsi.model.flow.subset.SubsetBrowseResult;
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class DMLServiceSqlserver implements DMLService{
	private static final Set<String> BINARY_COLUMN_TYPES = Set.of("VARBINARY", "BINARY", "IMAGE", "TIMESTAMP", "ROWVERSION");
	private static final Set<String> NATIONAL_CHAR_TYPES = Set.of("NCHAR", "NVARCHAR", "NTEXT");
	private static final Set<String> CHARACTER_COLUMN_TYPES = Set.of(
		"CHAR", "VARCHAR", "TEXT", "NCHAR", "NVARCHAR", "NTEXT", "XML", "SYSNAME", "UNIQUEIDENTIFIER"
	);
	private static final Set<String> SPATIAL_COLUMN_TYPES = Set.of("GEOGRAPHY", "GEOMETRY");
	private static final Set<String> GEOGRAPHY_COLUMN_TYPES = Set.of("GEOGRAPHY");
	private static final Set<String> HIERARCHYID_COLUMN_TYPES = Set.of("HIERARCHYID");
	private static final Set<String> DATE_COLUMN_TYPES = Set.of("DATE");
	private static final Set<String> TIME_COLUMN_TYPES = Set.of("TIME");
	private static final Set<String> DATETIME_COLUMN_TYPES = Set.of("DATETIME", "DATETIME2", "SMALLDATETIME");
	private static final Set<String> DATETIMEOFFSET_COLUMN_TYPES = Set.of("DATETIMEOFFSET");
	private static final DateTimeFormatter BACKUP_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
	private static final DateTimeFormatter BACKUP_DATE_TIME_NO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");


	private static final String GET_TABLE_DATA_PAGE_FORMAT = """
select * from (
	select %s, ROW_NUMBER() OVER (ORDER BY %s ) AS RowNum from %s AS t
) q WHERE q.RowNum > ? AND q.RowNum <= (? + ?)
;
            """;;
	private static final String SET_INSERT_IDENTITY_ON = "SET IDENTITY_INSERT %s ON";
	private static final String SET_INSERT_IDENTITY_OFF = "SET IDENTITY_INSERT %s OFF";
	private static final String GET_MAX_COLUMN_VALUE_SQL = "SELECT MAX(%s) as max_val FROM %s";
	private static final String UPDATE_SEQUENCE_SQL = "ALTER SEQUENCE %s RESTART WITH %d";

	private DataSource dataSource;
	private ReentrantLock globalLock;

	static String quotedTable(DbTable table) {
		return CommonHelpers.bracketQuotedQualified(table.getSchema(), table.getName());
	}

	static String quotedColumn(String columnName) {
		return CommonHelpers.bracketQuoted(columnName);
	}

	/**
	 * hierarchyid is selected as path text via {@code .ToString()} so JSON backups stay
	 * round-trippable with {@code hierarchyid::Parse(?)}. Raw JDBC bytes do not CAST cleanly.
	 */
	static String selectListForTable(DbTable table) {
		DbColumn[] columns = table.orderedColumns();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < columns.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			String quoted = quotedColumn(columns[i].getName());
			if (isHierarchyIdColumnType(columns[i])) {
				sb.append("t.").append(quoted).append(".ToString() AS ").append(quoted);
			} else {
				sb.append("t.").append(quoted);
			}
		}
		return sb.toString();
	}

	static String orderByForTable(DbTable table, List<String> sortColumnNames) {
		return sortColumnNames.stream().map(name -> "t." + quotedColumn(name)).collect(Collectors.joining(", "));
	}

	@Override
	public boolean supportsSubset() {
		return true;
	}

	@Override
	public int getTablePageSize(Integer expectedPageSize, DbTable table) {
		return expectedPageSize != null && expectedPageSize > 0 ? expectedPageSize : 1000;
	}

	@Override
	public long countRows(DbTable table) {
		String from = quotedTable(table);
		try (Connection conn = dataSource.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(String.format("SELECT COUNT(*) FROM %s", from))) {
			if (rs.next()) {
				return rs.getLong(1);
			}
			return 0L;
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-count-rows")
				.withExtra("table", table.qualifiedName())
				.withCause(e)
				.get();
		}
	}

	@Override
	public long countRows(DbTable table, String whereFragment) {
		try (Connection conn = dataSource.getConnection()) {
			return SqlSubsetSupport.countRows(conn, table, whereFragment, DMLServiceSqlserver::quotedTable);
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-count-rows")
				.withExtra("table", table.qualifiedName())
				.withCause(e)
				.get();
		}
	}

	@Override
	public Set<String> selectPrimaryKeys(DbTable table, String whereFragment, Integer limit) {
		try (Connection conn = dataSource.getConnection()) {
			return SqlSubsetSupport.selectPrimaryKeys(conn, table, whereFragment, limit,
				DMLServiceSqlserver::quotedTable, DMLServiceSqlserver::quotedColumn, LimitStyle.SQLSERVER_TOP);
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-select-primary-keys")
				.withExtra("table", table.qualifiedName())
				.withCause(e)
				.get();
		}
	}

	@Override
	public Set<String> selectParentPrimaryKeys(DbTable child, DbTable parent,
			List<String> childFkColumns, List<String> parentRefColumns, Collection<String> childPkKeys) {
		try (Connection conn = dataSource.getConnection()) {
			return SqlSubsetSupport.selectParentPrimaryKeys(conn, child, parent, childFkColumns, parentRefColumns,
				childPkKeys, DMLServiceSqlserver::quotedTable, DMLServiceSqlserver::quotedColumn);
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-select-parent-keys")
				.withExtra("child", child.qualifiedName())
				.withExtra("parent", parent.qualifiedName())
				.withCause(e)
				.get();
		}
	}

	@Override
	public SubsetBrowseResult browseRows(DbTable table, String whereFragment, Integer limit) {
		try (Connection conn = dataSource.getConnection()) {
			return SqlSubsetSupport.browseRows(conn, table, whereFragment, limit,
				DMLServiceSqlserver::quotedTable, DMLServiceSqlserver::quotedColumn, LimitStyle.SQLSERVER_TOP);
		} catch (SQLException e) {
			throw Exceptions.server("unable-to-browse-rows")
				.withExtra("table", table.qualifiedName())
				.withCause(e)
				.get();
		}
	}

    @Override
    public TableDataPage getTableDataPage(Request request){
		try(Connection conn = dataSource.getConnection()){
			List<String> sortColumns;
			if (!CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())) {
				sortColumns = request.getTable().getPkColumnNames();
			} else {
				List<String> orderable = request.getTable().orderableColumnNames();
				sortColumns = orderable.isEmpty() ? List.of() : orderable;
			}
			String sortColumnNames = sortColumns.isEmpty()
				? "(SELECT NULL)"
				: orderByForTable(request.getTable(), sortColumns);
			String from = quotedTable(request.getTable());
			String selectList = selectListForTable(request.getTable());
			PreparedStatement ps;
			List<String> primaryKeys = request.getPrimaryKeys();
			if (primaryKeys != null && !primaryKeys.isEmpty()) {
				String orderBy = request.getTable().getPkColumnNames().stream()
					.map(c -> "t." + quotedColumn(c)).collect(Collectors.joining(", "));
				String sql = SqlSubsetSupport.buildKeyedSelectSql(request.getTable(), selectList, orderBy,
					primaryKeys.size(), DMLServiceSqlserver::quotedTable, DMLServiceSqlserver::quotedColumn);
				log.info("subset sql for {} :{} keys={}", from, sql, primaryKeys.size());
				ps = conn.prepareStatement(sql);
				SqlSubsetSupport.bindPkKeys(ps, 1, request.getTable(), primaryKeys);
			} else {
				String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, selectList, sortColumnNames, from);
				log.info("sql for {} :{} offset :{} count: {}", from, sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
				ps = conn.prepareStatement(sql);
				ps.setInt(1, request.getPageNum() * request.getPageSize());
				ps.setInt(2, request.getPageNum() * request.getPageSize());
				ps.setInt(3, request.getPageSize());
			}
			TableDataPage page = new TableDataPage();
			page.setRequest(request);
			String pkNames = request.getTable().joinedPkColumnNames();
			List<String> pkColumnNames = request.getTable().getPkColumnNames();
			DbColumn[] orderedColumns = request.getTable().orderedColumns();
			List<String> allColumnNames = new ArrayList<>();
			for (DbColumn column : orderedColumns) {
				allColumnNames.add(column.getName());
			}
			int numberOfColumns = allColumnNames.size();
			if(CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())){
				pkNames = "[RowNum]";
				pkColumnNames = List.of("RowNum");
				allColumnNames.add("RowNum");
				numberOfColumns++;
			}

			Map<Object, Object[]> tableData = new HashMap<>();
			ResultSet rs = ps.executeQuery();
			while(rs.next()){
				Object[] cellValues = new Object[numberOfColumns];
				int columnIndex = 0;
				Object pk = null;
				StringBuilder pkBuilder = new StringBuilder();
				Map<String, Object> pkVals = new HashMap<>();
				log.trace("{} pk {} for row {}", request.getTable().getName(), pkNames, pk);
				for(int colIdx = 0; colIdx < allColumnNames.size(); colIdx++){
					String columnName = allColumnNames.get(colIdx);
					DbColumn columnMeta = colIdx < orderedColumns.length ? orderedColumns[colIdx] : null;
					if(!pkColumnNames.contains(columnName)){
						Object val = readColumnValue(rs, columnName, columnMeta);
						log.trace("{} column {} value {}", request.getTable().getName(), columnName, val);
						cellValues[columnIndex++] = val;
					}else{
						/* Same reader as non-PK — getObject() returns null for hierarchyid/geography UDTs. */
						Object pkVal = readColumnValue(rs, columnName, columnMeta);
						cellValues[columnIndex++] = pkVal;
						if(pkColumnNames.size() == 1){
							pk = pkVal;
						}else{
							pkVals.put(columnName, pkVal);
							if(pkBuilder.length() > 0){
								pkBuilder.append(DataSourceFactory.PK_VALUES_SEPERATOR);
							}
							pkBuilder.append(pkVal != null ? pkVal.toString() : "null");
						}
					}
				}
				if(pk == null){
					pk = pkBuilder.toString();
				}				
				tableData.put(pk, cellValues);
			}
			page.setTableData(tableData);
			page.setHasMorePage(page.getTableData().size() >= request.getPageSize());
			log.info("{} page for {} created", request.getPageNum(), request.getTable().getName());
			return page;
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("unable-to-read-data").withExtra("request", request).withCause(e).get();
		}
	}
    @Override
    public int writePageData(DbTable table, DataPage dataPage){
		try(Connection conn = dataSource.getConnection()){
			boolean previousAutoCommit = conn.getAutoCommit();
			boolean identityInsertOn = false;
			Boolean previousBulkCopy = null;
			ISQLServerConnection sqlServerConn = null;
			String from = quotedTable(table);
			try {
				conn.setAutoCommit(false);
				DbColumn[] orderedColumns = table.orderedColumns();
				StringBuilder sqlBuilder = new StringBuilder("insert into ").append(from).append("(");
				StringBuilder paramsBuilder = new StringBuilder("(");
				for(int i = 0; i < orderedColumns.length; i++){
					sqlBuilder.append(quotedColumn(orderedColumns[i].getName()));
					paramsBuilder.append(parameterPlaceholder(orderedColumns[i]));
					if(i < orderedColumns.length - 1){
						sqlBuilder.append(", ");
						paramsBuilder.append(", ");
					}
				}
				paramsBuilder.append(")");
				sqlBuilder.append(") values ").append(paramsBuilder.toString());
				/*
				 * useBulkCopyForBatchInsert cannot insert explicit identity values (mssql-jdbc #1606/#2221).
				 * Disable bulk-copy for this connection before prepareStatement when the table has identity.
				 */
				boolean hasIdentity = Arrays.stream(orderedColumns).anyMatch(DbColumn::isIdentity);
				if(hasIdentity){
					if (conn.isWrapperFor(ISQLServerConnection.class)) {
						sqlServerConn = conn.unwrap(ISQLServerConnection.class);
						previousBulkCopy = sqlServerConn.getUseBulkCopyForBatchInsert();
						sqlServerConn.setUseBulkCopyForBatchInsert(false);
					}
					try (Statement identityStmt = conn.createStatement()) {
						identityStmt.execute(String.format(SET_INSERT_IDENTITY_ON, from));
					}
					identityInsertOn = true;
				}
				String insertSql = sqlBuilder.toString();
				log.info("for {} insert sql :{}", table.getName(), insertSql);
				try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
					dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
						for(int i=0; i < orderedColumns.length; i++){
							setColumnValue(ps, i + 1, orderedColumns[i], e.getValue()[i]);
						}
						ps.addBatch();
					}));
					int[] results = ps.executeBatch();
					conn.commit();
					log.info("for {} page {} batch inserted {} rows", table.getName(), dataPage.getPageNum(), results.length);
				}
			} catch (Exception e) {
				try {
					conn.rollback();
				} catch (SQLException rollbackEx) {
					log.warn("rollback failed for table {} page {}", table.getName(), dataPage.getPageNum(), rollbackEx);
				}
				throw e;
			} finally {
				if (identityInsertOn) {
					try (Statement identityStmt = conn.createStatement()) {
						identityStmt.execute(String.format(SET_INSERT_IDENTITY_OFF, from));
					} catch (SQLException identityOffEx) {
						log.warn("failed to turn off IDENTITY_INSERT for {}", from, identityOffEx);
					}
				}
				if (sqlServerConn != null && previousBulkCopy != null) {
					sqlServerConn.setUseBulkCopyForBatchInsert(previousBulkCopy);
				}
				try {
					conn.setAutoCommit(previousAutoCommit);
				} catch (SQLException autoCommitEx) {
					log.warn("failed to restore autocommit for table {} page {}", table.getName(), dataPage.getPageNum(), autoCommitEx);
				}
			}
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("unable-to-write-data").withExtra("table", table.getName()) .withExtra("pageNum", dataPage.getPageNum()).withCause(e).get();
		}finally{
			if(globalLock != null && globalLock.isHeldByCurrentThread()){
				globalLock.unlock();
			}
		}
		return 0;		
	}

	static String parameterPlaceholder(DbColumn column) {
		if (isHierarchyIdColumnType(column)) {
			return "hierarchyid::Parse(?)";
		}
		return "?";
	}

	static void setColumnValue(PreparedStatement ps, int parameterIndex, DbColumn column, Object value) throws SQLException {
		if (value == null) {
			ps.setNull(parameterIndex, nullSqlType(column));
			return;
		}
		if (isHierarchyIdColumnType(column)) {
			setHierarchyIdValue(ps, parameterIndex, value);
			return;
		}
		if (isSpatialColumnType(column)) {
			setSpatialValue(ps, parameterIndex, column, value);
			return;
		}
		if (isTemporalColumnType(column)) {
			setTemporalValue(ps, parameterIndex, column, value);
			return;
		}
		if (value instanceof CustomSerializedColumn serializedColumn) {
			if (isBinaryColumnType(column)) {
				ps.setBytes(parameterIndex, serializedColumn.getData());
			} else {
				setCharacterValue(ps, parameterIndex, column, serializedColumn.getData());
			}
			return;
		}
		if (value instanceof Map<?, ?> mapValue && isDeserializedBinaryColumn(column, mapValue)) {
			Object data = mapValue.get("data");
			if (data == null) {
				ps.setNull(parameterIndex, Types.VARBINARY);
				return;
			}
			ps.setBytes(parameterIndex, decodeBinaryData(data));
			return;
		}
		if (isBinaryColumnType(column)) {
			ps.setBytes(parameterIndex, decodeBinaryData(value));
			return;
		}
		if (isCharacterColumnType(column)) {
			setCharacterValue(ps, parameterIndex, column, value);
			return;
		}
		if (value instanceof byte[]) {
			/* Never bind raw bytes into non-binary columns (MSSQL rejects varbinary→date etc.). */
			throw Exceptions.server("unexpected-binary-value")
					.withExtra("column", column.getName())
					.withExtra("columnType", column.getColumnType())
					.get();
		}
		ps.setObject(parameterIndex, value);
	}

	static void setTemporalValue(PreparedStatement ps, int parameterIndex, DbColumn column, Object value) throws SQLException {
		if (isDateColumnType(column)) {
			java.sql.Date date = toSqlDate(value);
			if (date == null) {
				ps.setNull(parameterIndex, Types.DATE);
			} else {
				ps.setDate(parameterIndex, date);
			}
			return;
		}
		if (isTimeColumnType(column)) {
			Time time = toSqlTime(value);
			if (time == null) {
				ps.setNull(parameterIndex, Types.TIME);
			} else {
				ps.setTime(parameterIndex, time);
			}
			return;
		}
		/* datetime / datetime2 / smalldatetime / datetimeoffset */
		Timestamp timestamp = toSqlTimestamp(value);
		if (timestamp == null) {
			ps.setNull(parameterIndex, Types.TIMESTAMP);
		} else {
			ps.setTimestamp(parameterIndex, timestamp);
		}
	}

	static java.sql.Date toSqlDate(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof java.sql.Date date) {
			return date;
		}
		if (value instanceof LocalDate localDate) {
			return java.sql.Date.valueOf(localDate);
		}
		if (value instanceof Timestamp timestamp) {
			return java.sql.Date.valueOf(timestamp.toLocalDateTime().toLocalDate());
		}
		if (value instanceof java.util.Date date) {
			return new java.sql.Date(date.getTime());
		}
		if (value instanceof Number number) {
			return new java.sql.Date(number.longValue());
		}
		if (value instanceof String str) {
			String trimmed = str.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			return java.sql.Date.valueOf(parseLocalDate(trimmed));
		}
		throw Exceptions.server("invalid-date-value")
				.withExtra("valueType", value.getClass().getName())
				.withExtra("value", String.valueOf(value))
				.get();
	}

	static Time toSqlTime(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Time time) {
			return time;
		}
		if (value instanceof LocalTime localTime) {
			return Time.valueOf(localTime);
		}
		if (value instanceof Timestamp timestamp) {
			return Time.valueOf(timestamp.toLocalDateTime().toLocalTime());
		}
		if (value instanceof String str) {
			String trimmed = str.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			return Time.valueOf(LocalTime.parse(trimmed));
		}
		throw Exceptions.server("invalid-time-value")
				.withExtra("valueType", value.getClass().getName())
				.get();
	}

	static Timestamp toSqlTimestamp(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Timestamp timestamp) {
			return timestamp;
		}
		if (value instanceof java.sql.Date date) {
			return new Timestamp(date.getTime());
		}
		if (value instanceof java.util.Date date) {
			return new Timestamp(date.getTime());
		}
		if (value instanceof LocalDateTime localDateTime) {
			return Timestamp.valueOf(localDateTime);
		}
		if (value instanceof LocalDate localDate) {
			return Timestamp.valueOf(localDate.atStartOfDay());
		}
		if (value instanceof Instant instant) {
			return Timestamp.from(instant);
		}
		if (value instanceof Number number) {
			return new Timestamp(number.longValue());
		}
		if (value instanceof String str) {
			String trimmed = str.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			return Timestamp.valueOf(parseLocalDateTime(trimmed));
		}
		throw Exceptions.server("invalid-timestamp-value")
				.withExtra("valueType", value.getClass().getName())
				.withExtra("value", String.valueOf(value))
				.get();
	}

	static LocalDate parseLocalDate(String value) {
		try {
			return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
		} catch (DateTimeParseException ignored) {
			return parseLocalDateTime(value).toLocalDate();
		}
	}

	static LocalDateTime parseLocalDateTime(String value) {
		try {
			return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} catch (DateTimeParseException ignored) {
		}
		try {
			return LocalDateTime.parse(value, BACKUP_DATE_TIME);
		} catch (DateTimeParseException ignored) {
		}
		try {
			return LocalDateTime.parse(value, BACKUP_DATE_TIME_NO_MILLIS);
		} catch (DateTimeParseException ignored) {
		}
		return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
	}

	/**
	 * hierarchyid must be path text ({@code /}, {@code /1/2/}). JDBC binary from getBytes does not
	 * round-trip through CAST/Parse; backups should use {@link #selectListForTable}.
	 */
	static void setHierarchyIdValue(PreparedStatement ps, int parameterIndex, Object value) throws SQLException {
		String path = toHierarchyIdPath(value);
		if (path == null) {
			ps.setNull(parameterIndex, Types.NVARCHAR);
			return;
		}
		ps.setString(parameterIndex, path);
	}

	static String toHierarchyIdPath(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String str) {
			String trimmed = str.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			if (looksLikeHierarchyPath(trimmed)) {
				return trimmed;
			}
			throw Exceptions.server("hierarchyid-requires-path")
					.withExtra("hint", "expected path like /1/2/; re-backup after hierarchyid.ToString() fix")
					.withExtra("valuePreview", trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed)
					.get();
		}
		if (value instanceof CustomSerializedColumn serialized) {
			return toHierarchyIdPath(serialized.getData());
		}
		if (value instanceof Map<?, ?> map) {
			Object data = map.containsKey("data") ? map.get("data") : map.get("value");
			return toHierarchyIdPath(data);
		}
		if (value instanceof byte[] bytes) {
			String asUtf8 = new String(bytes, StandardCharsets.UTF_8).trim();
			if (looksLikeHierarchyPath(asUtf8)) {
				return asUtf8;
			}
			throw Exceptions.server("hierarchyid-requires-path")
					.withExtra("hint", "hierarchyid binary is not supported; re-backup after agent update")
					.get();
		}
		if (value instanceof List<?>) {
			return toHierarchyIdPath(decodeBinaryData(value));
		}
		throw Exceptions.server("unsupported-hierarchyid-value")
				.withExtra("valueType", value.getClass().getName())
				.get();
	}

	static boolean looksLikeHierarchyPath(String value) {
		return value != null && value.startsWith("/");
	}

	static void setSpatialValue(PreparedStatement ps, int parameterIndex, DbColumn column, Object value) throws SQLException {
		ISQLServerPreparedStatement sps = ps.unwrap(ISQLServerPreparedStatement.class);
		if (value instanceof Geography geography) {
			sps.setGeography(parameterIndex, geography);
			return;
		}
		if (value instanceof Geometry geometry) {
			sps.setGeometry(parameterIndex, geometry);
			return;
		}
		if (value instanceof String str && looksLikeWkt(str.trim())) {
			String wkt = str.trim();
			if (isGeographyColumnType(column)) {
				sps.setGeography(parameterIndex, Geography.STGeomFromText(wkt, 4326));
			} else {
				sps.setGeometry(parameterIndex, Geometry.STGeomFromText(wkt, 0));
			}
			return;
		}
		byte[] clr = extractSpatialBytes(value);
		if (clr == null) {
			ps.setNull(parameterIndex, Types.NULL);
			return;
		}
		if (isGeographyColumnType(column)) {
			sps.setGeography(parameterIndex, Geography.deserialize(clr));
		} else {
			sps.setGeometry(parameterIndex, Geometry.deserialize(clr));
		}
	}

	/**
	 * Spatial values in backups are SQL Server CLR bytes (often base64).
	 */
	static byte[] extractSpatialBytes(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof byte[] bytes) {
			return bytes;
		}
		if (value instanceof CustomSerializedColumn serialized) {
			return serialized.getData();
		}
		if (value instanceof Map<?, ?> map) {
			Object data = map.containsKey("data") ? map.get("data") : map.get("value");
			if (data == null) {
				return null;
			}
			return extractSpatialBytes(data);
		}
		if (value instanceof String str) {
			String trimmed = str.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			return decodeBinaryData(trimmed);
		}
		if (value instanceof List<?>) {
			return decodeBinaryData(value);
		}
		throw Exceptions.server("unsupported-spatial-value")
				.withExtra("valueType", value.getClass().getName())
				.get();
	}

	static boolean looksLikeWkt(String value) {
		String upper = value.toUpperCase();
		return upper.startsWith("POINT")
				|| upper.startsWith("LINESTRING")
				|| upper.startsWith("POLYGON")
				|| upper.startsWith("MULTI")
				|| upper.startsWith("GEOMETRYCOLLECTION")
				|| upper.startsWith("CIRCULARSTRING")
				|| upper.startsWith("COMPOUNDCURVE")
				|| upper.startsWith("CURVEPOLYGON")
				|| upper.startsWith("FULLGLOBE");
	}

	static void setCharacterValue(PreparedStatement ps, int parameterIndex, DbColumn column, Object value) throws SQLException {
		String text = toCharacterData(column, value);
		if (text == null) {
			ps.setNull(parameterIndex, nullSqlType(column));
			return;
		}
		if (isNationalCharacterType(column)) {
			ps.setNString(parameterIndex, text);
		} else {
			ps.setString(parameterIndex, text);
		}
	}

	static String toCharacterData(DbColumn column, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String str) {
			return str;
		}
		if (value instanceof byte[] bytes) {
			return new String(bytes, isNationalCharacterType(column) ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
		}
		if (value instanceof Map<?, ?> mapValue) {
			Object nested = mapValue.containsKey("data") ? mapValue.get("data") : mapValue.get("value");
			if (nested != null && nested != value) {
				return toCharacterData(column, nested);
			}
		}
		return value.toString();
	}

	static Object readColumnValue(ResultSet rs, String columnName, DbColumn column) throws SQLException {
		if (column != null && isSpatialColumnType(column)) {
			return readSpatialValue(rs, columnName, column);
		}
		if (column != null && isHierarchyIdColumnType(column)) {
			return readHierarchyIdValue(rs, columnName);
		}
		if (column != null && isTemporalColumnType(column)) {
			return readTemporalValue(rs, columnName, column);
		}
		Object val = rs.getObject(columnName);
		if (val == null || rs.wasNull()) {
			return null;
		}
		if (val instanceof NClob nclob) {
			return nclob.getSubString(1, (int) Math.min(nclob.length(), Integer.MAX_VALUE));
		}
		if (val instanceof Clob clob) {
			return clob.getSubString(1, (int) Math.min(clob.length(), Integer.MAX_VALUE));
		}
		if (val instanceof Reader reader) {
			try {
				StringBuilder sb = new StringBuilder();
				char[] buf = new char[4096];
				int n;
				while ((n = reader.read(buf)) >= 0) {
					sb.append(buf, 0, n);
				}
				return sb.toString();
			} catch (Exception e) {
				throw Exceptions.server("unable-to-read-character-stream")
					.withExtra("column", columnName)
					.withCause(e)
					.get();
			}
		}
		if (val instanceof byte[] bytes) {
			if (column != null && isCharacterColumnType(column)) {
				return new String(bytes, isNationalCharacterType(column) ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
			}
			return bytes;
		}
		return val;
	}

	/**
	 * Column is already path text when selected via {@code .ToString()} in {@link #selectListForTable}.
	 */
	static Object readHierarchyIdValue(ResultSet rs, String columnName) throws SQLException {
		String path = rs.getString(columnName);
		if (path == null || rs.wasNull()) {
			return null;
		}
		return path;
	}

	/** ISO date/time strings for JSON-stable round-trip (avoids java.sql.Date → byte[] pitfalls). */
	static Object readTemporalValue(ResultSet rs, String columnName, DbColumn column) throws SQLException {
		if (isDateColumnType(column)) {
			java.sql.Date date = rs.getDate(columnName);
			return date == null || rs.wasNull() ? null : date.toLocalDate().toString();
		}
		if (isTimeColumnType(column)) {
			Time time = rs.getTime(columnName);
			return time == null || rs.wasNull() ? null : time.toLocalTime().toString();
		}
		Timestamp timestamp = rs.getTimestamp(columnName);
		if (timestamp == null || rs.wasNull()) {
			return null;
		}
		return timestamp.toLocalDateTime().format(BACKUP_DATE_TIME);
	}

	/** Returns SQL Server CLR spatial bytes for stable JSON/base64 round-trip. */
	static byte[] readSpatialValue(ResultSet rs, String columnName, DbColumn column) throws SQLException {
		SQLServerResultSet srs = rs.unwrap(SQLServerResultSet.class);
		if (isGeographyColumnType(column)) {
			Geography geography = srs.getGeography(columnName);
			return geography == null || geography.isNull() ? null : geography.serialize();
		}
		Geometry geometry = srs.getGeometry(columnName);
		return geometry == null || geometry.isNull() ? null : geometry.serialize();
	}

	static int nullSqlType(DbColumn column) {
		if (isHierarchyIdColumnType(column)) {
			return Types.NVARCHAR;
		}
		if (isDateColumnType(column)) {
			return Types.DATE;
		}
		if (isTimeColumnType(column)) {
			return Types.TIME;
		}
		if (isTemporalColumnType(column)) {
			return Types.TIMESTAMP;
		}
		if (isBinaryColumnType(column) || isSpatialColumnType(column)) {
			return Types.VARBINARY;
		}
		if (isNationalCharacterType(column)) {
			return Types.NVARCHAR;
		}
		if (isCharacterColumnType(column)) {
			return Types.VARCHAR;
		}
		return Types.NULL;
	}

	static boolean isBinaryColumnType(DbColumn column) {
		return isBinaryColumnType(column.getDataType()) || isBinaryColumnType(column.getColumnType());
	}

	static boolean isBinaryColumnType(Object columnType) {
		return columnType != null && BINARY_COLUMN_TYPES.contains(columnType.toString().toUpperCase());
	}

	static boolean isSpatialColumnType(DbColumn column) {
		return isSpatialColumnType(column.getDataType()) || isSpatialColumnType(column.getColumnType());
	}

	static boolean isSpatialColumnType(Object columnType) {
		return columnType != null && SPATIAL_COLUMN_TYPES.contains(columnType.toString().toUpperCase());
	}

	static boolean isGeographyColumnType(DbColumn column) {
		return isGeographyColumnType(column.getDataType()) || isGeographyColumnType(column.getColumnType());
	}

	static boolean isGeographyColumnType(Object columnType) {
		return columnType != null && GEOGRAPHY_COLUMN_TYPES.contains(columnType.toString().toUpperCase());
	}

	static boolean isHierarchyIdColumnType(DbColumn column) {
		return isHierarchyIdColumnType(column.getDataType()) || isHierarchyIdColumnType(column.getColumnType());
	}

	static boolean isHierarchyIdColumnType(Object columnType) {
		return columnType != null && HIERARCHYID_COLUMN_TYPES.contains(columnType.toString().toUpperCase());
	}

	static boolean isTemporalColumnType(DbColumn column) {
		return isDateColumnType(column) || isTimeColumnType(column)
				|| isDateTimeColumnType(column) || isDateTimeOffsetColumnType(column);
	}

	static boolean isDateColumnType(DbColumn column) {
		return matchesType(column, DATE_COLUMN_TYPES);
	}

	static boolean isTimeColumnType(DbColumn column) {
		return matchesType(column, TIME_COLUMN_TYPES);
	}

	static boolean isDateTimeColumnType(DbColumn column) {
		return matchesType(column, DATETIME_COLUMN_TYPES);
	}

	static boolean isDateTimeOffsetColumnType(DbColumn column) {
		return matchesType(column, DATETIMEOFFSET_COLUMN_TYPES);
	}

	private static boolean matchesType(DbColumn column, Set<String> types) {
		return types.contains(normalizeTypeName(column.getDataType()))
				|| types.contains(normalizeTypeName(column.getColumnType()));
	}

	private static String normalizeTypeName(Object columnType) {
		if (columnType == null) {
			return "";
		}
		String type = columnType.toString().trim().toUpperCase();
		int paren = type.indexOf('(');
		return paren >= 0 ? type.substring(0, paren).trim() : type;
	}

	static boolean isCharacterColumnType(DbColumn column) {
		return isCharacterColumnType(column.getDataType()) || isCharacterColumnType(column.getColumnType());
	}

	static boolean isCharacterColumnType(Object columnType) {
		return columnType != null && CHARACTER_COLUMN_TYPES.contains(columnType.toString().toUpperCase());
	}

	static boolean isNationalCharacterType(DbColumn column) {
		return isNationalCharacterType(column.getDataType()) || isNationalCharacterType(column.getColumnType());
	}

	static boolean isNationalCharacterType(Object columnType) {
		return columnType != null && NATIONAL_CHAR_TYPES.contains(columnType.toString().toUpperCase());
	}

	/**
	 * Only treat JSON maps as binary when the column (or embedded dbType) is actually binary.
	 * A bare {@code dataId} key is not enough — that false-positive bound varbinary into ntext.
	 */
	static boolean isDeserializedBinaryColumn(DbColumn column, Map<?, ?> mapValue) {
		return isBinaryColumnType(column) || isBinaryColumnType(mapValue.get("dbType"));
	}

	static byte[] decodeBinaryData(Object data) {
		if (data == null) {
			return null;
		}
		if (data instanceof byte[] bytes) {
			return bytes;
		}
		if (data instanceof String encodedStr) {
			return Base64.getDecoder().decode(encodedStr);
		}
		if (data instanceof List<?> list) {
			byte[] bytes = new byte[list.size()];
			for (int i = 0; i < list.size(); i++) {
				bytes[i] = ((Number) list.get(i)).byteValue();
			}
			return bytes;
		}
		throw Exceptions.server("binary-data-type-not-supported").withExtra("dataType", data.getClass().getName()).get();
	}

	@Override
	public boolean clearTables(String... tableNames) {
		try(Connection conn = dataSource.getConnection()){
			Statement s = conn.createStatement();
			for(String tableName : tableNames){
				s.addBatch("delete from " + CommonHelpers.bracketQuotedQualified(tableName));
			}
			s.executeBatch();
			return true;
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
	}

	@Override
	public Long getMaxColumnValue(String qualifiedTableName, String columnName) {
        String sql = String.format(GET_MAX_COLUMN_VALUE_SQL,
			quotedColumn(columnName),
			CommonHelpers.bracketQuotedQualified(qualifiedTableName));
        try (Connection conn = dataSource.getConnection();
			Statement stmt = conn.createStatement();) {
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                Object maxVal = rs.getObject("max_val");
                if (maxVal != null) {
                    if (maxVal instanceof Number) {
                        return ((Number) maxVal).longValue();
                    }
                }
            }
			rs.close();
        } catch (Exception e) {
            log.warn("Error getting max value for column {} in table {}", columnName, qualifiedTableName, e);
        }
        return null;
    }

	@Override
    public void updateSequence(String qualifiedSequenceName, Long newValue) {
		try (Connection conn = dataSource.getConnection();
			PreparedStatement ps = conn.prepareStatement(String.format(UPDATE_SEQUENCE_SQL,
				CommonHelpers.bracketQuotedQualified(qualifiedSequenceName), newValue))) {
			ps.executeUpdate();
		} catch (SQLException e) {
			log.warn("Error updating sequence {} to value {}", qualifiedSequenceName, newValue, e);
		}
    }

    @Override
    public void close() throws Exception {
    }
}
