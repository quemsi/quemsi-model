package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import javax.sql.DataSource;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.Column;
import com.quemsi.model.flow.db.sql.DbModel.DbTable;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.quemsi.model.flow.in.TableDataPage;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class DataSourceFactoryMySql implements DataSourceFactory {
	private static final String SQL_FOR_COLUMNS = """
SELECT cols.TABLE_NAME, cols.COLUMN_NAME, cols.ORDINAL_POSITION,
    cols.CHARACTER_MAXIMUM_LENGTH, cols.COLUMN_TYPE, cols.DATA_TYPE, cols.CHARACTER_OCTET_LENGTH, cols.NUMERIC_PRECISION, cols.NUMERIC_SCALE,
    cols.COLUMN_KEY, cols.COLUMN_DEFAULT, cols.IS_NULLABLE
    ,  refs.CONSTRAINT_NAME, refs.REFERENCED_TABLE_SCHEMA, refs.REFERENCED_TABLE_NAME, refs.REFERENCED_COLUMN_NAME
FROM INFORMATION_SCHEMA.`COLUMNS` as cols
  LEFT JOIN INFORMATION_SCHEMA.`KEY_COLUMN_USAGE` AS refs
	ON refs.TABLE_SCHEMA=cols.TABLE_SCHEMA
    AND refs.REFERENCED_TABLE_SCHEMA=cols.TABLE_SCHEMA
    AND refs.TABLE_NAME=cols.TABLE_NAME
    AND refs.COLUMN_NAME=cols.COLUMN_NAME
where cols.TABLE_SCHEMA = ?
order by cols.TABLE_NAME, cols.ORDINAL_POSITION
;
			""";
	private String name;
	private String dbName;
	private String url;
	private String username;
	private String password;
	private DataSource instance;
	
	@Override
	public synchronized DataSource getDataSource() {
		if(instance == null) {
			MysqlDataSource ds =new MysqlDataSource();
			ds.setUrl(this.url);
			ds.setPassword(password);
			ds.setUser(username);
			instance = ds;
		}
		return instance;
	}

	@Override
	public DbModel getDbModel() {
		DbModel dbModel = new DbModel();
		Queue<ReferenceInfo> referenceInfos = new LinkedList<>();
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement ps = con.prepareStatement(SQL_FOR_COLUMNS);
		){
			ps.setString(1, dbName);
			ResultSet rs = ps.executeQuery();
			while(rs.next()){
				String tableName = rs.getString("TABLE_NAME");
				String columnName = rs.getString("COLUMN_NAME");
				Integer ordinalPosition = rs.getInt("ORDINAL_POSITION");
				Integer maxLength = rs.getInt("CHARACTER_MAXIMUM_LENGTH");
				String columnType = rs.getString("COLUMN_TYPE");
				String dataType = rs.getString("DATA_TYPE");
				Integer numPrecision = rs.getInt("NUMERIC_PRECISION");
				Integer numScale = rs.getInt("NUMERIC_SCALE");
				String columnKey = rs.getString("COLUMN_KEY");
				String columnDefault = rs.getString("COLUMN_DEFAULT");
				String nullable = rs.getString("IS_NULLABLE");
				String constName = rs.getString("CONSTRAINT_NAME");
				String refTable = rs.getString("REFERENCED_TABLE_NAME");
				String refColumn = rs.getString("REFERENCED_COLUMN_NAME");
				DbTable table = dbModel.crateIfAbsent(tableName);
				Column column = table.addColumn(columnName, dataType, null, null, ordinalPosition, columnType, maxLength, numPrecision, numScale, columnKey, columnDefault, nullable);
				if(refColumn != null){
					referenceInfos.add(ReferenceInfo.builder().column(column).constraintName(constName).refTableName(refTable).refColumnName(refColumn).build());
				}
				if("PRI".equals(columnKey)){
					table.getPkColumnNames().add(columnName);
				}
			}
			while(!referenceInfos.isEmpty()){
				ReferenceInfo refInfo = referenceInfos.poll();
				DbTable rTable = dbModel.findTable(refInfo.getRefTableName()).orElseThrow(Exceptions.server("unknow-table-in-fk")
						.withExtra("tableName", refInfo.getColumn().getTable().getName()).withExtra("columnName", refInfo.getColumn().getName()).withExtra("refTable", refInfo.getRefTableName()).withExtra("refColumn", refInfo.getRefColumnName()).supplier());
				Column rColumn = rTable.findColumn(refInfo.getRefColumnName()).orElseThrow(Exceptions.server("unknow-column-in-fk")
					.withExtra("tableName", refInfo.getColumn().getTable().getName()).withExtra("columnName", refInfo.getColumn().getName()).withExtra("refTable", refInfo.getRefTableName()).withExtra("refColumn", refInfo.getRefColumnName()).supplier());
				refInfo.getColumn().getTable().addReference(refInfo.getColumn(), rColumn, refInfo.getConstraintName());
			}
		}catch(Exception e){
			throw Exceptions.server("unable-to-build-dbmodel").withCause(e).get();
		}
		return dbModel;
	}

	private static final String GET_TABLE_DATA_PAGE_FORMAT = "select * from %s t order by %s limit ?, ?";
	public TableDataPage getTableDataPage(TableDataPage.Request request){
		try(Connection conn = getDataSource().getConnection()){
			String sql = String.format(GET_TABLE_DATA_PAGE_FORMAT, request.getTable().getName(), request.getTable().joinedPkColumnNames());
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
				for(String columnName : request.getTable().columnNames()){
					if(!request.getTable().getPkColumnNames().contains(columnName)){
						Object val = rs.getObject(columnName);
						log.trace("{} column {} value {}", request.getTable().getName(), columnName, val);
						cellValues[columnIndex++] = val;
					}else{
						if(request.getTable().getPkColumnNames().size() == 1){
							String pkName = request.getTable().getPkColumnNames().iterator().next();
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

	public int writePageData(DbTable table, DataPage dataPage){
		try(Connection conn = getDataSource().getConnection()){
			StringBuilder sqlBuilder = new StringBuilder("insert into ").append(table.getName()).append("(");
			StringBuilder paramsBuilder = new StringBuilder("(");
			int counter = 0;
 			for(String columnName : table.columnNames()){
				sqlBuilder.append(columnName);
				paramsBuilder.append("?");
				counter++;
				if(counter < table.columnNames().size()){
					sqlBuilder.append(", ");
					paramsBuilder.append(", ");
				}
			}
			paramsBuilder.append(");");
			sqlBuilder.append(") values ").append(paramsBuilder.toString());
			String insertSql = sqlBuilder.toString();
			log.info("for {} insert sql :{}", table.getName(), insertSql);
			Column[] orderedColumns = table.orderedColumns();
			PreparedStatement ps = conn.prepareStatement(insertSql);
			dataPage.getData().entrySet().forEach(Exceptions.wrapConsumer(e -> {
				for(int i=0; i < orderedColumns.length; i++){
					Column c = orderedColumns[i];	
					ps.setObject(c.getOrdinalPosition(), e.getValue()[i]);
				}
				ps.addBatch();
			}));
			int[] results = ps.executeBatch();
			log.info("for {} page {} batch result {}", table.getName(), dataPage.getPageNum(), results);
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("unable-to-write-data").withExtra("table", table.getName()) .withExtra("pageNum", dataPage.getPageNum()).withCause(e).get();
		}
		return 0;		
	}

	@Override
	public boolean clearTables(String... tableNames) {
		try(Connection conn = getDataSource().getConnection()){
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
}
