package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;

import javax.sql.DataSource;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.DataSourceFactory;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.Column;
import com.quemsi.model.flow.db.sql.DbModel.DbTable;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.in.TableData.DataPage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
	private static final String SQL_FOR_INDEXES = """
SELECT
    st.TABLE_NAME,
    st.INDEX_NAME,
    st.COLUMN_NAME,
    st.SEQ_IN_INDEX,
    st.NON_UNIQUE
FROM INFORMATION_SCHEMA.STATISTICS st
WHERE TABLE_SCHEMA = ?
order by st.TABLE_NAME, st.INDEX_NAME, st.SEQ_IN_INDEX;
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
			HikariConfig config = new HikariConfig();
			config.setJdbcUrl(this.url);
			config.setPassword(password);
			config.setUsername(username);
			HikariDataSource ds =new HikariDataSource(config);
			instance = ds;
		}
		return instance;
	}

	@Override
	public DbModel getDbModel() {
		DbModel dbModel = new DbModel();
		dbModel.setSourceType(DatasourceType.MYSQL.name());
		try(
			Connection con = getDataSource().getConnection();
			PreparedStatement ps = con.prepareStatement(SQL_FOR_COLUMNS);
			PreparedStatement ist = con.prepareStatement(SQL_FOR_INDEXES); 
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
				Column column = table.addColumn(columnName, dataType, ordinalPosition, columnType, maxLength, numPrecision, numScale, columnKey, columnDefault, nullable);
				if(refColumn != null){
					dbModel.getReferenceInfos().add(ReferenceInfo.builder().srcTable(tableName).srcColumnName(column.getName()).constraintName(constName).refTableName(refTable).refColumnName(refColumn).build());
				}
				if("PRI".equals(columnKey)){
					table.getPkColumnNames().add(columnName);
				}
			}
			ist.setString(1, dbName);
			ResultSet irs = ist.executeQuery();
			IndexInfo cur = null;
			while (irs.next()) {
				String tableName = irs.getString("TABLE_NAME");
				String indexName = irs.getString("INDEX_NAME");
				String columnName = irs.getString("COLUMN_NAME");
				boolean nonUnique = irs.getBoolean("NON_UNIQUE");
				if(cur == null || !tableName.equals(cur.getTableName()) || !indexName.equals(cur.getIndexName())){
					if(cur != null){
						CommonOps.getOrInit(dbModel.getIndexes(), cur.getTableName(), () -> new HashMap<>()).put(cur.getIndexName(), cur);
					}
					cur = new IndexInfo(tableName, indexName, !nonUnique);
				}
				cur.getColumns().add(columnName);
			}
			if(cur != null){
				CommonOps.getOrInit(dbModel.getIndexes(), cur.getTableName(), HashMap::new).put(cur.getIndexName(), cur);
			}
			dbModel.build();
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

	public void disableConstraints(Set<ReferenceInfo> constraints){
		try(Connection conn = getDataSource().getConnection()){
			for(ReferenceInfo refInfo : constraints) {
				StringBuilder sb = new StringBuilder("ALTER TABLE ");
				sb.append(refInfo.getSrcTable()).append(" DROP FOREIGN KEY ")
				.append(refInfo.getConstraintName()).append(";");
				try{
					String dropConstraintSql = sb.toString();
					log.info("drop constraint sql :{}", dropConstraintSql);
					Statement s = conn.createStatement();
					s.executeUpdate(dropConstraintSql);
				}catch(SQLException ignore){
					log.info("ignored ignored disable constraint " + refInfo.getConstraintName(), ignore);
				}
			}
		}catch(SQLException ignore){
			log.error("ignored disable constraints", ignore);
		}
	}

	public void enableContraints(Set<ReferenceInfo> constraints){
		try(Connection conn = getDataSource().getConnection()){
			for(ReferenceInfo refInfo : constraints) {
				StringBuilder sb = new StringBuilder("ALTER TABLE ");
				sb.append(refInfo.getSrcTable()).append(" ADD CONSTRAINT ")
				.append(refInfo.getConstraintName())
				.append(" FOREIGN KEY (").append(refInfo.getSrcColumnName())
				.append(") REFERENCES ").append(refInfo.getRefTableName()).append("(").append(refInfo.getRefColumnName()).append(");");
				try{
					String dropConstraintSql = sb.toString();
					log.info("drop constraint sql :{}", dropConstraintSql);
					Statement s = conn.createStatement();
					s.executeUpdate(dropConstraintSql);
				}catch(SQLException ignore){
					log.info("ignored enable constraint : " + refInfo.getConstraintName(), ignore);
				}
			}
		}catch(SQLException ignore){
			log.error("ignored disable constraints", ignore);
		}
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

	@Override
	public boolean dropTables(String... tableNames) {
		try(Connection conn = getDataSource().getConnection()){
			Statement s = conn.createStatement();
			for(String tableName : tableNames){
				s.addBatch("DROP TABLE IF EXISTS " + tableName + ";");
			}
			s.executeBatch();
			return true;
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
	}

	@Override
	public void createTables(DbModel dbModel) {
		LinkedList<StringBuilder> scripts = new LinkedList<>();
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream().collect(Collectors.groupingBy(r -> r.getSrcTable()));
		for(String tableName : dbModel.orderedTableNames()){
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (").append(System.lineSeparator());
			Column[] columns = table.orderedColumns();
			for(Column c : columns){
				sb.append("  ").append(c.getName()).append(" ").append(c.getColumnType());
				if(!c.isNullable()){
					sb.append(" NOT NULL");
				}
				if(c.getColumnDefault() == null){
					if(c.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT").contains(c.getColumnType().toUpperCase())){
						sb.append(" DEFAULT NULL");
					}
				}else{
					sb.append(" DEFAULT " + c.getColumnDefault());
				}
				sb.append(",").append(System.lineSeparator());
			}
			if(table.getPkColumnNames().size() > 0){
				sb.append("  ").append("PRIMARY KEY (");
				Iterator<String> cIt = table.getPkColumnNames().iterator();
				while(cIt.hasNext()){
					String cName = cIt.next();
					sb.append(cName);
					if(cIt.hasNext()){
						sb.append(", ");
					}
				}
				sb.append(")");
			}
			if(dbModel.getIndexes().containsKey(tableName)){
				Map<String, IndexInfo> indexes = dbModel.getIndexes().get(tableName);
				Iterator<String> indNameIt = indexes.keySet().iterator();
				while(indNameIt.hasNext()){
					String indName = indNameIt.next();
					if(!"PRIMARY".equals(indName)){
						sb.append(",").append(System.lineSeparator());
						sb.append("  KEY ").append(indName).append(" (");
						IndexInfo indCols = indexes.get(indName);
						Iterator<String> icIt = indCols.getColumns().iterator();
						while(icIt.hasNext()){
							String ic = icIt.next();
							sb.append(ic);
							if(icIt.hasNext()){
								sb.append(" ,");
							}
						}
						sb.append(")");
					}

				};
			}
			if(tableReferences.containsKey(tableName)){
				Iterator<ReferenceInfo> refIt = tableReferences.get(tableName).iterator();
				while(refIt.hasNext()){
					ReferenceInfo ref = refIt.next();
					sb.append(",").append(System.lineSeparator())
						.append("  CONSTRAINT ").append(ref.getConstraintName()).append(" FOREIGN KEY (").append(ref.getSrcColumnName()).append(") REFERENCES ")
						.append(ref.getRefTableName()).append(" (").append(ref.getRefColumnName()).append(")");
				}
			}
			sb.append(System.lineSeparator()).append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
			log.info("create script for {} : {}", tableName, sb.toString());
			scripts.add(sb);
		}
		try(Connection conn = getDataSource().getConnection()){
			Statement s = conn.createStatement();
			for(StringBuilder sb : scripts){
				s.executeUpdate(sb.toString());
			}
		}catch(SQLException ignore){
			log.info("create tables sql", ignore);
		}
	}
}
