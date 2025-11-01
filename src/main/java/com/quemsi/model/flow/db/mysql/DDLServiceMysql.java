package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServiceMysql implements DDLService{
    private DataSource dataSource;
    
    @Override
	public boolean dropTables(String... tableNames) {
		try(Connection conn = dataSource.getConnection()){
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

    public void disableConstraints(Set<ReferenceInfo> constraints){
        for(ReferenceInfo refInfo : constraints) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ");
            sb.append(refInfo.getSrcTableName()).append(" DROP FOREIGN KEY ")
            .append(refInfo.getConstraintName()).append(";");
            try(Connection conn = dataSource.getConnection()){
                String dropConstraintSql = sb.toString();
                log.info("drop constraint sql :{}", dropConstraintSql);
                Statement s = conn.createStatement();
                s.executeUpdate(dropConstraintSql);
            }catch(SQLException ignore){
                log.info("ignored ignored disable constraint " + refInfo.getConstraintName(), ignore);
            }
        }
	}

    @Override
    public void enableContraints(Set<ReferenceInfo> constraints){
        for(ReferenceInfo refInfo : constraints) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ");
            sb.append(refInfo.getSrcTableName()).append(" ADD CONSTRAINT ")
            .append(refInfo.getConstraintName())
        	.append(" FOREIGN KEY (");
            Iterator<String> cIt = refInfo.getSrcColumnNames().iterator();
            while(cIt.hasNext()){
                String cName = cIt.next();
                sb.append(cName);
                if(cIt.hasNext()){
                    sb.append(", ");
                }
            }
            sb.append(") REFERENCES ").append(refInfo.getRefTableName()).append("(");
            cIt = refInfo.getRefColumnNames().iterator();
            while(cIt.hasNext()){
                String cName = cIt.next();
                sb.append(cName);
                if(cIt.hasNext()){
                    sb.append(", ");
                }
            }
            sb.append(");");
            try(Connection conn = dataSource.getConnection()){
                String enableConstraintSql = sb.toString();
                log.info("enable constraint sql :{}", enableConstraintSql);
                Statement s = conn.createStatement();
                s.executeUpdate(enableConstraintSql);
            }catch(SQLException ignore){
                log.info("ignored enable constraint : " + refInfo.getConstraintName(), ignore);
            }
        }
	}

    @Override
	public void createTables(DbModel dbModel) {
		LinkedList<StringBuilder> scripts = new LinkedList<>();
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream().collect(Collectors.groupingBy(r -> r.getSrcTableName()));
		for(String tableName : dbModel.orderedTableNames()){
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			for(DbColumn c : columns){
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
						.append("  CONSTRAINT ").append(ref.getConstraintName()).append(" FOREIGN KEY (");
                    Iterator<String> cIt = ref.getSrcColumnNames().iterator();
                    while(cIt.hasNext()){
                        String cName = cIt.next();
                        sb.append(cName);
                        if(cIt.hasNext()){
                            sb.append(", ");
                        }
                    }
                    sb.append(") REFERENCES ").append(ref.getRefTableName()).append(" (");
                    cIt = ref.getRefColumnNames().iterator();
                    while(cIt.hasNext()){
                        String cName = cIt.next();
                        sb.append(cName);
                        if(cIt.hasNext()){
                            sb.append(", ");
                        }
                    }
                    sb.append(")");
				}
			}
			sb.append(System.lineSeparator()).append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
			log.info("create script for {} : {}", tableName, sb.toString());
			scripts.add(sb);
		}
		try(Connection conn = dataSource.getConnection()){
			Statement s = conn.createStatement();
			for(StringBuilder sb : scripts){
				s.executeUpdate(sb.toString());
			}
		}catch(SQLException ignore){
			log.info("create tables sql", ignore);
		}
	}

	@Override
	public boolean checkSchema(String schema) throws SQLException{
		return true;
	}

    @Override
    public void close() throws Exception {
    }
}
