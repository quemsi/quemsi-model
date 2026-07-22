package com.quemsi.model.flow.db.oracle;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DMLService;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.CustomSerializedColumn;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;
import com.quemsi.model.flow.in.TableDataPage.Request;
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class DMLServiceOracle implements DMLService {
	private static final Set<String> BINARY_COLUMN_TYPES = Set.of("BLOB", "RAW", "LONG RAW");
	private static final Set<String> TEMPORAL_COLUMN_TYPES = Set.of(
		"DATE", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE"
	);
	private static final DateTimeFormatter BACKUP_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
	private static final DateTimeFormatter BACKUP_DATE_TIME_FORMAT_NO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	private static final DateTimeFormatter FLEXIBLE_LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
		.append(DateTimeFormatter.ISO_LOCAL_DATE)
		.optionalStart()
		.appendLiteral('T')
		.optionalEnd()
		.optionalStart()
		.appendLiteral(' ')
		.optionalEnd()
		.optionalStart()
		.appendPattern("HH:mm:ss")
		.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
		.optionalEnd()
		.toFormatter();
	private static final String GET_TABLE_DATA_PAGE_FORMAT = """
select * from %s
order by %s
offset ? rows fetch next ? rows only
;
			""";
	private static final String GET_MAX_COLUMN_VALUE_SQL = "SELECT MAX(%s) as max_val FROM %s";
	private static final String UPDATE_SEQUENCE_SQL = "ALTER SEQUENCE %s RESTART START WITH %d";

	private static final int maxRowsPerPage = 5_000;
	private DataSource dataSource;
	private ReentrantLock globalLock;

	@Override
	public int getTablePageSize(Integer expectedPageSize, DbTable table) {
		int expected = expectedPageSize != null && expectedPageSize > 0 ? expectedPageSize : 1000;
		return Math.min(maxRowsPerPage, expected);
	}

	@Override
	public TableDataPage getTableDataPage(Request request) {
		try (Connection conn = dataSource.getConnection()) {
			String sortColumnNames;
			if (!CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())) {
				sortColumnNames = request.getTable().getPkColumnNames().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
			} else {
				List<String> orderable = request.getTable().orderableColumnNames();
				sortColumnNames = orderable.isEmpty()
					? "ROWNUM"
					: orderable.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
			}
			String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, request.getTable().qualifiedName(), sortColumnNames);
			log.info("sql for {} :{} offset :{} count: {}", request.getTable().qualifiedName(), sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setInt(1, request.getPageNum() * request.getPageSize());
			ps.setInt(2, request.getPageSize());

			TableDataPage page = new TableDataPage();
			page.setRequest(request);
			List<String> pkColumnNames = new ArrayList<>(request.getTable().getPkColumnNames());
			List<String> allColumnNames = new ArrayList<>();
			for (DbColumn column : request.getTable().orderedColumns()) {
				allColumnNames.add(column.getName());
			}
			int numberOfColumns = allColumnNames.size();
			if (CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())) {
				pkColumnNames = List.of("ROWNUM");
				allColumnNames.add("ROWNUM");
				numberOfColumns++;
			}

			Map<Object, Object[]> tableData = new HashMap<>();
			ResultSet rs = ps.executeQuery();
			int rowNum = request.getPageNum() * request.getPageSize();
			while (rs.next()) {
				rowNum++;
				Object[] cellValues = new Object[numberOfColumns];
				int columnIndex = 0;
				Object pk = null;
				StringBuilder pkBuilder = new StringBuilder();
				for (String columnName : allColumnNames) {
					if ("ROWNUM".equals(columnName)) {
						pk = rowNum;
						cellValues[columnIndex++] = rowNum;
						continue;
					}
					if (!pkColumnNames.contains(columnName)) {
						Object val = readColumnValue(rs, columnName, request.getTable().column(columnName));
						cellValues[columnIndex++] = val;
					} else {
						if (pkColumnNames.size() == 1) {
							String pkName = pkColumnNames.iterator().next();
							pk = readColumnValue(rs, pkName, request.getTable().column(pkName));
							cellValues[columnIndex++] = pk;
						} else {
							Object pkVal = readColumnValue(rs, columnName, request.getTable().column(columnName));
							cellValues[columnIndex++] = pkVal;
							if (pkBuilder.length() > 0) {
								pkBuilder.append(DataSourceFactory.PK_VALUES_SEPERATOR);
							}
							pkBuilder.append(pkVal.toString());
						}
					}
				}
				if (pk == null) {
					pk = pkBuilder.toString();
				}
				tableData.put(pk, cellValues);
			}
			page.setTableData(tableData);
			page.setHasMorePage(page.getTableData().size() >= request.getPageSize());
			log.info("{} page for {} created", request.getPageNum(), request.getTable().getName());
			return page;
		} catch (Exception e) {
			throw Exceptions.server("unable-to-read-data").withExtra("request", request).withCause(e).get();
		}
	}

	private Object readColumnValue(ResultSet rs, String columnName, DbColumn column) throws SQLException {
		Object val = rs.getObject(columnName);
		if (val == null || rs.wasNull()) {
			return null;
		}
		if (val instanceof Blob blob) {
			try (InputStream is = blob.getBinaryStream()) {
				return toBinaryColumn(column, columnName, readAllBytes(is));
			} catch (Exception e) {
				throw Exceptions.server("unable-to-read-blob").withExtra("column", columnName).withCause(e).get();
			}
		}
		if (val instanceof byte[] bytes) {
			return toBinaryColumn(column, columnName, bytes);
		}
		if (val instanceof Clob clob) {
			return clob.getSubString(1, (int) clob.length());
		}
		return normalizeJdbcValue(val, rs, columnName, column);
	}

	private Object normalizeJdbcValue(Object val, ResultSet rs, String columnName, DbColumn column) throws SQLException {
		String className = val.getClass().getName();
		if (className.startsWith("oracle.sql.")) {
			if (className.contains("TIMESTAMP") || "oracle.sql.DATE".equals(className)) {
				return rs.getTimestamp(columnName);
			}
			if ("oracle.sql.INTERVALDS".equals(className) || "oracle.sql.INTERVALYM".equals(className)) {
				return val.toString();
			}
			if ("oracle.sql.RAW".equals(className)) {
				return toBinaryColumn(column, columnName, rs.getBytes(columnName));
			}
			return val.toString();
		}
		if (val instanceof java.util.Date && !(val instanceof java.sql.Date) && !(val instanceof java.sql.Timestamp)) {
			return new java.sql.Timestamp(((java.util.Date) val).getTime());
		}
		return val;
	}

	private byte[] readAllBytes(InputStream is) throws java.io.IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] chunk = new byte[8192];
		int read;
		while ((read = is.read(chunk)) != -1) {
			buffer.write(chunk, 0, read);
		}
		return buffer.toByteArray();
	}

	private CustomSerializedColumn.BinaryColumn toBinaryColumn(DbColumn column, String columnName, byte[] data) {
		return CustomSerializedColumn.BinaryColumn.builder()
			.dbType(column.getColumnType())
			.dataId(columnName)
			.data(data)
			.build();
	}

	private void setColumnValue(PreparedStatement ps, int parameterIndex, DbColumn column, Object value) throws SQLException {
		if (value == null) {
			ps.setNull(parameterIndex, Types.NULL);
			return;
		}
		if (value instanceof CustomSerializedColumn serializedColumn) {
			ps.setBytes(parameterIndex, serializedColumn.getData());
			return;
		}
		if (value instanceof Map<?, ?> mapValue) {
			if (isDeserializedBinaryColumn(column, mapValue)) {
				Object data = mapValue.get("data");
				if (data == null) {
					ps.setNull(parameterIndex, Types.BLOB);
					return;
				}
				if (isBinaryEncodedValue(data)) {
					ps.setBytes(parameterIndex, decodeBinaryData(data));
					return;
				}
				setColumnValue(ps, parameterIndex, column, data);
				return;
			}
			throw Exceptions.server("column-type-not-supported")
				.withExtra("column", column.getName())
				.withExtra("value", mapValue)
				.get();
		}
		if (isBinaryColumnType(column) && isBinaryEncodedValue(value)) {
			ps.setBytes(parameterIndex, decodeBinaryData(value));
			return;
		}
		if (isTemporalColumn(column)) {
			setTemporalValue(ps, parameterIndex, value);
			return;
		}
		if (value instanceof Timestamp timestamp) {
			ps.setTimestamp(parameterIndex, timestamp);
		} else if (value instanceof java.sql.Date date) {
			ps.setDate(parameterIndex, date);
		} else if (value instanceof java.sql.Time time) {
			ps.setTime(parameterIndex, time);
		} else if (value instanceof BigDecimal bigDecimal) {
			ps.setBigDecimal(parameterIndex, bigDecimal);
		} else if (value instanceof Double doubleVal) {
			ps.setDouble(parameterIndex, doubleVal);
		} else if (value instanceof Float floatVal) {
			ps.setFloat(parameterIndex, floatVal);
		} else if (value instanceof Number number) {
			ps.setLong(parameterIndex, number.longValue());
		} else if (value instanceof Boolean bool) {
			ps.setBoolean(parameterIndex, bool);
		} else if (value instanceof String str) {
			ps.setString(parameterIndex, str);
		} else {
			ps.setObject(parameterIndex, value);
		}
	}

	private void setTemporalValue(PreparedStatement ps, int parameterIndex, Object value) throws SQLException {
		if (value == null) {
			ps.setNull(parameterIndex, Types.TIMESTAMP);
			return;
		}
		Timestamp timestamp = toTimestamp(value);
		if (timestamp == null) {
			ps.setNull(parameterIndex, Types.TIMESTAMP);
			return;
		}
		ps.setTimestamp(parameterIndex, timestamp);
	}

	private Timestamp toTimestamp(Object value) {
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
			return parseTimestamp(trimmed);
		}
		throw Exceptions.server("invalid-timestamp-value")
			.withExtra("dataType", value.getClass().getName())
			.withExtra("value", value)
			.get();
	}

	private boolean isTemporalColumn(DbColumn column) {
		if (column == null) {
			return false;
		}
		return isTemporalType(column.getDataType()) || isTemporalType(column.getColumnType());
	}

	private boolean isTemporalType(String type) {
		if (type == null) {
			return false;
		}
		String normalized = type.toUpperCase();
		return TEMPORAL_COLUMN_TYPES.contains(normalized) || normalized.startsWith("TIMESTAMP");
	}

	private Timestamp parseTimestamp(String value) {
		try {
			return Timestamp.valueOf(LocalDateTime.parse(value, FLEXIBLE_LOCAL_DATE_TIME));
		} catch (DateTimeParseException ignored) {
		}
		try {
			return Timestamp.valueOf(LocalDateTime.parse(value, BACKUP_DATE_TIME_FORMAT));
		} catch (DateTimeParseException ignored) {
		}
		try {
			return Timestamp.valueOf(LocalDateTime.parse(value, BACKUP_DATE_TIME_FORMAT_NO_MILLIS));
		} catch (DateTimeParseException ignored) {
		}
		try {
			return Timestamp.valueOf(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay());
		} catch (DateTimeParseException ignored) {
		}
		if (value.length() >= 19 && value.charAt(10) == ' ') {
			try {
				return Timestamp.valueOf(value);
			} catch (IllegalArgumentException ignored) {
			}
		}
		throw Exceptions.server("invalid-timestamp-format").withExtra("value", value).get();
	}

	private boolean isDeserializedBinaryColumn(DbColumn column, Map<?, ?> mapValue) {
		return isBinaryColumnType(column)
			|| isBinaryColumnType(mapValue.get("dbType"))
			|| mapValue.containsKey("dataId");
	}

	private boolean isBinaryEncodedValue(Object value) {
		return value instanceof byte[]
			|| value instanceof String
			|| value instanceof List<?>;
	}

	private boolean isBinaryColumnType(DbColumn column) {
		return isBinaryColumnType(column.getColumnType()) || isBinaryColumnType(column.getDataType());
	}

	private boolean isBinaryColumnType(Object columnType) {
		return columnType != null && BINARY_COLUMN_TYPES.contains(columnType.toString().toUpperCase());
	}

	private byte[] decodeBinaryData(Object data) {
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

	private String quoteIdentifier(String columnName) {
		if (DatasourceFactoryOracle.RESERVED_KEYS.contains(columnName.toUpperCase())) {
			return "\"" + columnName + "\"";
		}
		return columnName;
	}

	@Override
	public int writePageData(DbTable table, DataPage dataPage) {
		try (Connection conn = dataSource.getConnection()) {
			boolean previousAutoCommit = conn.getAutoCommit();
			try {
				conn.setAutoCommit(false);
				StringBuilder sqlBuilder = new StringBuilder("insert into ").append(table.qualifiedName()).append("(");
				StringBuilder paramsBuilder = new StringBuilder("(");
				DbColumn[] columns = table.orderedColumns();
				int counter = 0;
				for (DbColumn column : columns) {
					sqlBuilder.append(quoteIdentifier(column.getName()));
					paramsBuilder.append("?");
					counter++;
					if (counter < columns.length) {
						sqlBuilder.append(", ");
						paramsBuilder.append(", ");
					}
				}
				paramsBuilder.append(")");
				sqlBuilder.append(") values ").append(paramsBuilder);
				String insertSql = sqlBuilder.toString();
				log.info("for {} insert sql :{}", table.getName(), insertSql);
				PreparedStatement ps = conn.prepareStatement(insertSql);
				dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
					for (int i = 0; i < columns.length; i++) {
						setColumnValue(ps, i + 1, columns[i], e.getValue()[i]);
					}
					ps.addBatch();
				}));
				int[] results = ps.executeBatch();
				conn.commit();
				log.info("for {} page {} batch inserted {} rows", table.getName(), dataPage.getPageNum(), results.length);
			} catch (Exception e) {
				try {
					conn.rollback();
				} catch (SQLException rollbackEx) {
					log.warn("rollback failed for table {} page {}", table.getName(), dataPage.getPageNum(), rollbackEx);
				}
				throw e;
			} finally {
				try {
					conn.setAutoCommit(previousAutoCommit);
				} catch (SQLException autoCommitEx) {
					log.warn("failed to restore autocommit for table {} page {}", table.getName(), dataPage.getPageNum(), autoCommitEx);
				}
			}
		} catch (Exception e) {
			throw Exceptions.server("unable-to-write-data").withExtra("table", table.getName()).withExtra("pageNum", dataPage.getPageNum()).withCause(e).get();
		} finally {
			if (globalLock != null && globalLock.isHeldByCurrentThread()) {
				globalLock.unlock();
			}
		}
		return 0;
	}

	@Override
	public boolean clearTables(String... tableNames) {
		try (Connection conn = dataSource.getConnection()) {
			Statement s = conn.createStatement();
			for (String tableName : tableNames) {
				s.addBatch("delete from " + tableName);
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
	}

	@Override
	public Long getMaxColumnValue(String qualifiedTableName, String columnName) {
		String sql = String.format(GET_MAX_COLUMN_VALUE_SQL, quoteIdentifier(columnName), qualifiedTableName);
		try (Connection conn = dataSource.getConnection();
			 Statement stmt = conn.createStatement()) {
			ResultSet rs = stmt.executeQuery(sql);
			if (rs.next()) {
				Object maxVal = rs.getObject("max_val");
				if (maxVal instanceof Number number) {
					return number.longValue();
				}
			}
		} catch (Exception e) {
			log.warn("Error getting max value for column {} in table {}", columnName, qualifiedTableName, e);
		}
		return null;
	}

	@Override
	public void updateSequence(String qualifiedSequenceName, Long newValue) {
		try (Connection conn = dataSource.getConnection();
			 PreparedStatement ps = conn.prepareStatement(String.format(UPDATE_SEQUENCE_SQL, qualifiedSequenceName, newValue))) {
			ps.executeUpdate();
		} catch (SQLException e) {
			log.warn("Error updating sequence {} to value {}", qualifiedSequenceName, newValue, e);
		}
	}

	@Override
	public void close() throws Exception {
	}
}
