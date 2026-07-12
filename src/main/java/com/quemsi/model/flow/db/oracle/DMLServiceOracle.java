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
import java.util.List;
import java.util.Map;
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
	private static final String GET_TABLE_DATA_PAGE_FORMAT = """
select * from %s
order by %s
offset ? rows fetch next ? rows only
;
			""";
	private static final String GET_MAX_COLUMN_VALUE_SQL = "SELECT MAX(%s) as max_val FROM %s";
	private static final String UPDATE_SEQUENCE_SQL = "ALTER SEQUENCE %s RESTART START WITH %d";

	private static final int maxPages = 10;
	private static final int maxRowsPerPage = 100000;
	private DataSource dataSource;
	private ReentrantLock globalLock;

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
	public TableDataPage getTableDataPage(Request request) {
		try (Connection conn = dataSource.getConnection()) {
			String sortColumnNames = !CommonHelpers.isEmptyOrNull(request.getTable().getPkColumnNames())
				? request.getTable().getPkColumnNames().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "))
				: request.getTable().getColumns().keySet().stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
			String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, request.getTable().qualifiedName(), sortColumnNames);
			log.info("sql for {} :{} offset :{} count: {}", request.getTable().qualifiedName(), sql, request.getPageNum() * request.getPageSize(), request.getPageSize());
			PreparedStatement ps = conn.prepareStatement(sql);
			ps.setInt(1, request.getPageNum() * request.getPageSize());
			ps.setInt(2, request.getPageSize());

			TableDataPage page = new TableDataPage();
			page.setRequest(request);
			List<String> pkColumnNames = new ArrayList<>(request.getTable().getPkColumnNames());
			List<String> allColumnNames = new ArrayList<>(request.getTable().columnNames());
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
				byte[] data = readAllBytes(is);
				return CustomSerializedColumn.BinaryColumn.builder()
					.dbType(column.getColumnType())
					.dataId(columnName)
					.data(data)
					.build();
			} catch (Exception e) {
				throw Exceptions.server("unable-to-read-blob").withExtra("column", columnName).withCause(e).get();
			}
		}
		if (val instanceof Clob clob) {
			return clob.getSubString(1, (int) clob.length());
		}
		return normalizeJdbcValue(val, rs, columnName);
	}

	private Object normalizeJdbcValue(Object val, ResultSet rs, String columnName) throws SQLException {
		String className = val.getClass().getName();
		if (className.startsWith("oracle.sql.")) {
			if (className.contains("TIMESTAMP") || "oracle.sql.DATE".equals(className)) {
				return rs.getTimestamp(columnName);
			}
			if ("oracle.sql.INTERVALDS".equals(className) || "oracle.sql.INTERVALYM".equals(className)) {
				return val.toString();
			}
			if ("oracle.sql.RAW".equals(className)) {
				return rs.getBytes(columnName);
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

	private String quoteIdentifier(String columnName) {
		if (DatasourceFactoryOracle.RESERVED_KEYS.contains(columnName.toUpperCase())) {
			return "\"" + columnName + "\"";
		}
		return columnName;
	}

	@Override
	public int writePageData(DbTable table, DataPage dataPage) {
		try (Connection conn = dataSource.getConnection()) {
			StringBuilder sqlBuilder = new StringBuilder("insert into ").append(table.qualifiedName()).append("(");
			StringBuilder paramsBuilder = new StringBuilder("(");
			int counter = 0;
			for (String columnName : table.columnNames()) {
				sqlBuilder.append(quoteIdentifier(columnName));
				paramsBuilder.append("?");
				counter++;
				if (counter < table.columnNames().size()) {
					sqlBuilder.append(", ");
					paramsBuilder.append(", ");
				}
			}
			paramsBuilder.append(")");
			sqlBuilder.append(") values ").append(paramsBuilder);
			String insertSql = sqlBuilder.toString();
			log.info("for {} insert sql :{}", table.getName(), insertSql);
			DbColumn[] orderedColumns = table.orderedColumns();
			PreparedStatement ps = conn.prepareStatement(insertSql);
			dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
				for (int i = 0; i < orderedColumns.length; i++) {
					DbColumn c = orderedColumns[i];
					Object value = e.getValue()[i];
					if (value == null) {
						ps.setNull(i + 1, Types.NULL);
					} else if (value instanceof CustomSerializedColumn serializedColumn) {
						ps.setBytes(i + 1, serializedColumn.getData());
					} else if ("BLOB".equalsIgnoreCase(c.getColumnType()) && value instanceof String encodedStr) {
						ps.setBytes(i + 1, Base64.getDecoder().decode(encodedStr));
					} else if (value instanceof Timestamp timestamp) {
						ps.setTimestamp(i + 1, timestamp);
					} else if (value instanceof java.sql.Date date) {
						ps.setDate(i + 1, date);
					} else if (value instanceof java.sql.Time time) {
						ps.setTime(i + 1, time);
					} else {
						ps.setObject(i + 1, value);
					}
				}
				ps.addBatch();
			}));
			int[] results = ps.executeBatch();
			log.info("for {} page {} batch result size {}", table.getName(), dataPage.getPageNum(), results.length);
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
