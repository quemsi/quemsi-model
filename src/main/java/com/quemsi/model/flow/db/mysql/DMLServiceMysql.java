package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
public class DMLServiceMysql implements DMLService{
    private static final String GET_TABLE_DATA_PAGE_FORMAT = "select * from %s t order by %s limit ?, ?";
	private static final String GET_MAX_COLUMN_VALUE_SQL = "SELECT MAX(`%s`) as max_val FROM %s";
	
	private static final int maxPages = 10;
	private static final int maxRowsPerPage = 100000;
	private DataSource dataSource;

	@Override
	public int getTablePageSize(Integer expectedPageSize, DbTable table) {
		int totalRows = 0;
		try (Connection conn = dataSource.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(String.format("SELECT COUNT(*) FROM %s", table.qualifiedName()))) {
			if (rs.next()) {
				totalRows = rs.getInt(1);
			}
		} catch (SQLException e) {
			log.warn("Could not determine row count for {}. Using expectedPageSize {}", table.qualifiedName(), expectedPageSize, e);
			return expectedPageSize;
		}
		int calculatedPageSize = (int) Math.ceil((double) totalRows / maxPages);
		return Math.min(maxRowsPerPage, Math.max(expectedPageSize, calculatedPageSize));
	}

    @Override
    public TableDataPage getTableDataPage(Request request){
		try(Connection conn = dataSource.getConnection()){
			String sortColumnNames;
			if (!CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())) {
				sortColumnNames = request.getTable().getPkColumnNames().stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
			} else {
				List<String> orderable = request.getTable().orderableColumnNames();
				sortColumnNames = orderable.isEmpty()
					? "NULL"
					: orderable.stream().map(c -> "`" + c + "`").collect(Collectors.joining(", "));
			}
			String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, request.getTable().getName(), sortColumnNames);
			log.info("sql for {} :{} offset :{} count: {}", request.getTable().getName(), sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setInt(1, request.getPageNum() * request.getPageSize());
			ps.setInt(2, request.getPageSize());
			
			TableDataPage page = new TableDataPage();
			page.setRequest(request);
			
			Map<Object, Object[]> tableData = new HashMap<>();
			ResultSet rs = ps.executeQuery();
			while(rs.next()){
				Object[] cellValues = new Object[request.getTable().getColumns().size()];
				int columnIndex = 0;
				Object pk = null;
				StringBuilder pkBuilder = new StringBuilder();
				Map<String, Object> pkVals = new HashMap<>();
				log.trace("{} pk {} for row {}", request.getTable().getName(), request.getTable().joinedPkColumnNames(), pk);
				if(CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())){
					pk = request.getSeqGenerator().getAndIncrement();
				}
				for(String columnName : request.getTable().columnNames()){
					DbColumn column = request.getTable().column(columnName);
					if(!request.getTable().getPkColumnNames().contains(columnName)){
						Object val = readColumnValue(rs, columnName, column);
						log.trace("{} column {} value {}", request.getTable().getName(), columnName, val);
						cellValues[columnIndex++] = val;
					}else{
						if(request.getTable().getPkColumnNames().size() == 1){
							String pkName = request.getTable().getPkColumnNames().iterator().next();
							pk = readColumnValue(rs, pkName, column);
							cellValues[columnIndex++] = pk;
						}else{
							Object pkVal = Exceptions.wrapSupplier(() -> readColumnValue(rs, columnName, column)).get();
							cellValues[columnIndex++] = pkVal;
							pkVals.put(columnName, pkVal);
							if(pkBuilder.length() > 0){
								pkBuilder.append(DataSourceFactory.PK_VALUES_SEPERATOR);
							}
							pkBuilder.append(pkVal.toString());
						}
					}
				}
				if(request.getTable().getPkColumnNames().size() > 1){
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
			try {
				try (Statement sessionStmt = conn.createStatement()) {
					sessionStmt.execute("SET FOREIGN_KEY_CHECKS=0, UNIQUE_CHECKS=0");
				}
				conn.setAutoCommit(false);
				StringBuilder sqlBuilder = new StringBuilder("insert into ").append(table.getName()).append("(");
				StringBuilder paramsBuilder = new StringBuilder("(");
				int counter = 0;
				for(String columnName : table.columnNames()){
					DbColumn column = table.column(columnName);
					sqlBuilder.append("`").append(columnName).append("`");
					if (isGeometryType(column)) {
						paramsBuilder.append("ST_GeomFromWKB(?)");
					} else {
						paramsBuilder.append("?");
					}
					counter++;
					if(counter < table.columnNames().size()){
						sqlBuilder.append(", ");
						paramsBuilder.append(", ");
					}
				}
				/* No trailing ';' — rewriteBatchedStatements rewrites PreparedStatement batches */
				paramsBuilder.append(")");
				sqlBuilder.append(") values ").append(paramsBuilder.toString());
				String insertSql = sqlBuilder.toString();
				log.info("for {} insert sql :{}", table.getName(), insertSql);
				DbColumn[] orderedColumns = table.orderedColumns();
				PreparedStatement ps = conn.prepareStatement(insertSql);
				dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
					for(int i=0; i < orderedColumns.length; i++){
						DbColumn c = orderedColumns[i];
						setColumnValue(ps, c.getOrdinalPosition(), c, e.getValue()[i]);
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
				try (Statement sessionStmt = conn.createStatement()) {
					sessionStmt.execute("SET FOREIGN_KEY_CHECKS=1, UNIQUE_CHECKS=1");
				} catch (SQLException restoreEx) {
					log.warn("failed to restore FOREIGN_KEY_CHECKS/UNIQUE_CHECKS for table {} page {}", table.getName(), dataPage.getPageNum(), restoreEx);
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
		}
		return 0;		
	}

    @Override
	public boolean clearTables(String... tableNames) {
		try(Connection conn = dataSource.getConnection()){
			Statement s = conn.createStatement();
			for(String tableName : tableNames){
				s.addBatch("delete from " + tableName);
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
		String sql = String.format(GET_MAX_COLUMN_VALUE_SQL, columnName, qualifiedTableName);
        try (Statement stmt = dataSource.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                Object maxVal = rs.getObject("max_val");
                if (maxVal != null) {
                    if (maxVal instanceof Number) {
                        return ((Number) maxVal).longValue();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error getting max value for column {} in table {}", columnName, qualifiedTableName, e);
        }
        return null;
	}
	@Override
	public void updateSequence(String qualifiedSequenceName, Long newValue) {
		throw Exceptions.server("not-supported-implemented").get();
	}
    @Override
    public void close() throws Exception {
        
    }

	private static Object readColumnValue(ResultSet rs, String columnName, DbColumn column) throws SQLException {
		if (isGeometryType(column)) {
			byte[] bytes = rs.getBytes(columnName);
			if (bytes == null || rs.wasNull()) {
				return null;
			}
			return CustomSerializedColumn.BinaryColumn.builder()
				.dbType(column.getColumnType() != null ? column.getColumnType() : column.getDataType())
				.dataId(columnName)
				.data(bytes)
				.build();
		}
		return normalizeReadValue(column, rs.getObject(columnName));
	}

	private static void setColumnValue(PreparedStatement ps, int parameterIndex, DbColumn column, Object value) throws SQLException {
		if (value == null) {
			ps.setNull(parameterIndex, Types.NULL);
			return;
		}
		if (isYearType(column)) {
			Integer year = toMysqlYearValue(value);
			if (year == null) {
				ps.setNull(parameterIndex, Types.SMALLINT);
			} else {
				ps.setInt(parameterIndex, year);
			}
			return;
		}
		if (isGeometryType(column)) {
			byte[] wkb = toWkbBytes(value);
			if (wkb == null) {
				ps.setNull(parameterIndex, Types.BINARY);
			} else {
				ps.setBytes(parameterIndex, wkb);
			}
			return;
		}
		ps.setObject(parameterIndex, value);
	}

	private static Object normalizeReadValue(DbColumn column, Object value) {
		if (value == null || column == null) {
			return value;
		}
		if (isYearType(column)) {
			return toMysqlYearValue(value);
		}
		return value;
	}

	private static final Set<String> GEOMETRY_TYPES = Set.of(
		"GEOMETRY", "POINT", "LINESTRING", "POLYGON",
		"MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION"
	);

	static boolean isGeometryType(DbColumn column) {
		return GEOMETRY_TYPES.contains(mysqlBaseType(column));
	}

	static boolean isYearType(DbColumn column) {
		return "YEAR".equals(mysqlBaseType(column));
	}

	private static String mysqlBaseType(DbColumn column) {
		if (column == null) {
			return "";
		}
		String type = column.getColumnType() != null ? column.getColumnType() : column.getDataType();
		if (type == null) {
			return "";
		}
		String base = type.trim().toUpperCase();
		int paren = base.indexOf('(');
		if (paren >= 0) {
			base = base.substring(0, paren).trim();
		}
		return base;
	}

	/**
	 * Converts backup geometry payloads to WKB for ST_GeomFromWKB.
	 * Accepts MySQL internal (SRID + WKB) bytes from JDBC getBytes, base64 strings, and BinaryColumn maps.
	 */
	static byte[] toWkbBytes(Object value) {
		byte[] raw = extractBinaryPayload(value);
		if (raw == null) {
			return null;
		}
		return stripMysqlSridPrefix(raw);
	}

	static byte[] extractBinaryPayload(Object value) {
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
			Object data = map.get("data");
			if (data == null) {
				return null;
			}
			return extractBinaryPayload(data);
		}
		if (value instanceof String str) {
			String trimmed = str.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			try {
				return Base64.getDecoder().decode(trimmed);
			} catch (IllegalArgumentException ex) {
				throw Exceptions.server("unsupported-geometry-value")
					.withExtra("hint", "expected base64 WKB/geometry bytes")
					.withExtra("valuePreview", trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed)
					.get();
			}
		}
		throw Exceptions.server("unsupported-geometry-value")
			.withExtra("valueType", value.getClass().getName())
			.get();
	}

	/**
	 * MySQL geometry wire format is 4-byte little-endian SRID followed by WKB.
	 * Plain WKB starts with endian marker 0/1; SRID 0 is also 0x00000000 so detect carefully.
	 */
	static byte[] stripMysqlSridPrefix(byte[] data) {
		if (data == null || data.length < 9) {
			return data;
		}
		byte endianAt4 = data[4];
		if (endianAt4 != 0 && endianAt4 != 1) {
			return data;
		}
		/* LE WKB endian at offset 4 ⇒ MySQL SRID prefix (plain LE WKB has endian at offset 0) */
		if (endianAt4 == 1) {
			return Arrays.copyOfRange(data, 4, data.length);
		}
		/* endianAt4 == 0: plain LE WKB has data[0]==1; MySQL SRID 0 + BE WKB has 0x00000000 prefix */
		if (data[0] == 1) {
			return data;
		}
		if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 0) {
			return Arrays.copyOfRange(data, 4, data.length);
		}
		return data;
	}

	/**
	 * Converts backup/JDBC YEAR representations to a 4-digit year.
	 * Handles Short/Integer years, java.sql.Date (Connector/J default), ISO date strings, and epoch millis.
	 */
	static Integer toMysqlYearValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			long lv = number.longValue();
			if (lv >= 0 && lv <= 9999) {
				return (int) lv;
			}
			/* Jackson often serializes java.sql.Date as epoch millis */
			return new java.sql.Date(lv).toLocalDate().getYear();
		}
		if (value instanceof java.sql.Date date) {
			return date.toLocalDate().getYear();
		}
		if (value instanceof Timestamp timestamp) {
			return timestamp.toLocalDateTime().getYear();
		}
		if (value instanceof LocalDate localDate) {
			return localDate.getYear();
		}
		if (value instanceof LocalDateTime localDateTime) {
			return localDateTime.getYear();
		}
		if (value instanceof java.util.Date date) {
			return date.toInstant().atZone(ZoneOffset.UTC).getYear();
		}
		if (value instanceof String str) {
			String trimmed = str.trim();
			if (trimmed.matches("\\d{1,4}")) {
				return Integer.parseInt(trimmed);
			}
			if (trimmed.length() >= 4 && trimmed.substring(0, 4).matches("\\d{4}")) {
				return Integer.parseInt(trimmed.substring(0, 4));
			}
		}
		throw Exceptions.server("unsupported-year-value")
			.withExtra("value", value)
			.withExtra("valueType", value.getClass().getName())
			.get();
	}

}
