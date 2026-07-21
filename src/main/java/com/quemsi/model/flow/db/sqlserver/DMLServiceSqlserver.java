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
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
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
public class DMLServiceSqlserver implements DMLService{
	private static final Set<String> BINARY_COLUMN_TYPES = Set.of("VARBINARY", "BINARY", "IMAGE");
	private static final Set<String> NATIONAL_CHAR_TYPES = Set.of("NCHAR", "NVARCHAR", "NTEXT");
	private static final Set<String> CHARACTER_COLUMN_TYPES = Set.of(
		"CHAR", "VARCHAR", "TEXT", "NCHAR", "NVARCHAR", "NTEXT", "XML", "SYSNAME"
	);

	private static final String GET_TABLE_DATA_PAGE_FORMAT = """
select * from (
	select *, ROW_NUMBER() OVER (ORDER BY %s ) AS RowNum from %s
) q WHERE q.RowNum > ? AND q.RowNum <= (? + ?)
;
            """;;
	private static final String SET_INSERT_IDENTITY_ON = "SET IDENTITY_INSERT %s ON;";
	private static final String SET_INSERT_IDENTITY_OFF = "SET IDENTITY_INSERT %s OFF;";
	private static final String GET_MAX_COLUMN_VALUE_SQL = "SELECT MAX(%s) as max_val FROM %s";
	private static final String UPDATE_SEQUENCE_SQL = "ALTER SEQUENCE %s RESTART WITH %d";

	private static final int maxPages = 10;
	private static final int maxRowsPerPage = 100000;
	private DataSource dataSource;
	private ReentrantLock globalLock;

	static String quotedTable(DbTable table) {
		return CommonHelpers.bracketQuotedQualified(table.getSchema(), table.getName());
	}

	static String quotedColumn(String columnName) {
		return CommonHelpers.bracketQuoted(columnName);
	}

	@Override
	public int getTablePageSize(Integer expectedPageSize, DbTable table) {
		int totalRows = 0;
		String from = quotedTable(table);
		try (Connection conn = dataSource.getConnection();
			 Statement stmt = conn.createStatement();
			 ResultSet rs = stmt.executeQuery(String.format("SELECT COUNT(*) FROM %s", from))) {
			if (rs.next()) {
				totalRows = rs.getInt(1);
			}
		} catch (SQLException e) {
			log.warn("Could not determine row count for {}. Using expectedPageSize {}", from, expectedPageSize, e);
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
				sortColumnNames = request.getTable().getPkColumnNames().stream().map(DMLServiceSqlserver::quotedColumn).collect(Collectors.joining(", "));
			} else {
				List<String> orderable = request.getTable().orderableColumnNames();
				sortColumnNames = orderable.isEmpty()
					? "(SELECT NULL)"
					: orderable.stream().map(DMLServiceSqlserver::quotedColumn).collect(Collectors.joining(", "));
			}
			String from = quotedTable(request.getTable());
			String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, sortColumnNames, from);
			log.info("sql for {} :{} offset :{} count: {}", from, sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setInt(1, request.getPageNum() * request.getPageSize());
			ps.setInt(2, request.getPageNum() * request.getPageSize());
			ps.setInt(3, request.getPageSize());
			
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
						if(pkColumnNames.size() == 1){
							String pkName = pkColumnNames.iterator().next();
							pk = rs.getObject(pkName);
							cellValues[columnIndex++] = pk;
						}else{
							Object pkVal = Exceptions.wrapSupplier(() -> rs.getObject(columnName)).get();
							cellValues[columnIndex++] = pkVal;
							pkVals.put(columnName, pkVal);
							if(pkBuilder.length() > 0){
								pkBuilder.append(DataSourceFactory.PK_VALUES_SEPERATOR);
							}
							pkBuilder.append(pkVal.toString());
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
			try {
				conn.setAutoCommit(false);
				String from = quotedTable(table);
				DbColumn[] orderedColumns = table.orderedColumns();
				StringBuilder sqlBuilder = new StringBuilder("insert into ").append(from).append("(");
				StringBuilder paramsBuilder = new StringBuilder("(");
				for(int i = 0; i < orderedColumns.length; i++){
					sqlBuilder.append(quotedColumn(orderedColumns[i].getName()));
					paramsBuilder.append("?");
					if(i < orderedColumns.length - 1){
						sqlBuilder.append(", ");
						paramsBuilder.append(", ");
					}
				}
				paramsBuilder.append(")");
				sqlBuilder.append(") values ").append(paramsBuilder.toString());
				String insertSql = sqlBuilder.toString();
				boolean hasIdentity = Arrays.stream(orderedColumns).map(c -> c.isIdentity()).reduce(Boolean.FALSE, (c, v) -> c || v);
				if(hasIdentity){
					String setIdentityOnSql = String.format(SET_INSERT_IDENTITY_ON, from);
					String setIdentityOffSql = String.format(SET_INSERT_IDENTITY_OFF, from);
					insertSql = setIdentityOnSql + insertSql + setIdentityOffSql;
				}
				log.info("for {} insert sql :{}", table.getName(), insertSql);
				PreparedStatement ps = conn.prepareStatement(insertSql);
				dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
					for(int i=0; i < orderedColumns.length; i++){
						setColumnValue(ps, i + 1, orderedColumns[i], e.getValue()[i]);
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

	static void setColumnValue(PreparedStatement ps, int parameterIndex, DbColumn column, Object value) throws SQLException {
		if (value == null) {
			ps.setNull(parameterIndex, nullSqlType(column));
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
		if (value instanceof byte[] bytes) {
			/* Avoid setObject(byte[]) which the MSSQL driver binds as varbinary. */
			ps.setBytes(parameterIndex, bytes);
			return;
		}
		ps.setObject(parameterIndex, value);
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

	static int nullSqlType(DbColumn column) {
		if (isBinaryColumnType(column)) {
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
