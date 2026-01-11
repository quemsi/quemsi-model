package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
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

    @Override
    public boolean dropSequences(String... sequenceNames) {
        log.info("drop sequences: {}", Arrays.toString(sequenceNames));
        return true;
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
                sb.append("`").append(cName).append("`");
                if(cIt.hasNext()){
                    sb.append(", ");
                }
            }
            sb.append(") REFERENCES ").append(refInfo.getRefTableName()).append("(");
            cIt = refInfo.getRefColumnNames().iterator();
            while(cIt.hasNext()){
                String cName = cIt.next();
                sb.append("`").append(cName).append("`");
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
		Map<String, ContraintInfo> constraintInfos = dbModel.getContraintInfos().stream().collect(Collectors.toMap(ContraintInfo::getConstraintName, Function.identity()));
		for(String tableName : dbModel.orderedTableNames()){
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			int index = 0;
            for(DbColumn c : columns){
				sb.append("  ").append("`").append(c.getName()).append("`").append(" ").append(c.getColumnType());
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
                if(index < columns.length - 1){
                    sb.append(",").append(System.lineSeparator());
                }
                index++;
			}
			if(table.getPkColumnNames().size() > 0){
				sb.append(",").append(System.lineSeparator());
                sb.append("  ").append("PRIMARY KEY (");
				Iterator<String> cIt = table.getPkColumnNames().iterator();
				while(cIt.hasNext()){
					String cName = cIt.next();
					sb.append("`").append(cName).append("`");
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
					if(!"PRIMARY".equals(indName) && !constraintInfos.containsKey(indName)){
						sb.append(",").append(System.lineSeparator());
						sb.append("  KEY ").append(indName);
						IndexInfo indCols = indexes.get(indName);
						sb.append(" (");
						Iterator<String> icIt = indCols.getColumns().iterator();
						while(icIt.hasNext()){
							String ic = icIt.next();
							sb.append("`").append(ic).append("`");
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
                        sb.append("`").append(cName).append("`");
                        if(cIt.hasNext()){
                            sb.append(", ");
                        }
                    }
                    sb.append(") REFERENCES ").append(ref.getRefTableName()).append(" (");
                    cIt = ref.getRefColumnNames().iterator();
                    while(cIt.hasNext()){
                        String cName = cIt.next();
                        sb.append("`").append(cName).append("`");
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
		for(ContraintInfo contraintInfo : dbModel.getContraintInfos()){
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(contraintInfo.getTableName()).append(" ADD CONSTRAINT ").append(contraintInfo.getConstraintName()).append(" UNIQUE").append(" (");
			Iterator<String> cIt = contraintInfo.getColumnNames().iterator();
			while(cIt.hasNext()){
				String cName = cIt.next();
				sb.append("`").append(cName).append("`");
				if(cIt.hasNext()){
					sb.append(", ");
				}
			}
			sb.append(");");
			log.info("create unique constraint {} for table {} : {}", contraintInfo.getConstraintName(), contraintInfo.getTableName(), sb.toString());
			scripts.add(sb);
		}
		for(CheckConstraint checkConstraint : dbModel.getCheckConstraints()){
			StringBuilder sb = new StringBuilder("ALTER TABLE ");
			// Backtick table name if it contains special characters
			String tableName = checkConstraint.getTableName();
			if(tableName.contains(".") || tableName.contains("`")){
				sb.append("`").append(tableName.replace("`", "``")).append("`");
			} else {
				sb.append(tableName);
			}
			sb.append(" ADD CONSTRAINT ");
			// Backtick constraint name if it contains special characters (like dots)
			String constraintName = checkConstraint.getConstraintName();
			if(constraintName.contains(".") || constraintName.contains("`") || constraintName.contains("-") || constraintName.contains(" ")){
				sb.append("`").append(constraintName.replace("`", "``")).append("`");
			} else {
				sb.append(constraintName);
			}
			String convertedCondef = convertCheckClause(checkConstraint.getCondef());
			sb.append(" CHECK (").append(convertedCondef).append(");");
			log.info("create check constraint {} for table {} : {}", checkConstraint.getConstraintName(), checkConstraint.getTableName(), sb.toString());
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

    /**
     * Converts MySQL's internal CHECK_CLAUSE format to standard MySQL CHECK syntax.
     * Converts regexp_like(column, _utf8mb4'pattern') to column REGEXP 'pattern'
     */
    private String convertCheckClause(String checkClause) {
        if (checkClause == null || checkClause.trim().isEmpty()) {
            return checkClause;
        }
        
        // Check if it's a regexp_like format
        String trimmed = checkClause.trim();
        if (trimmed.toLowerCase().startsWith("regexp_like(")) {
            try {
                // Pattern: regexp_like(`column`, _utf8mb4\'pattern\')
                // Find the opening paren and comma (handling backticks in column name)
                int openParen = trimmed.indexOf('(');
                int closeParen = trimmed.lastIndexOf(')');
                if (openParen > 0 && closeParen > openParen) {
                    String args = trimmed.substring(openParen + 1, closeParen);
                    
                    // Find the comma that separates column from pattern
                    // Need to handle backticks in column name
                    int commaIndex = -1;
                    boolean inBackticks = false;
                    for (int i = 0; i < args.length(); i++) {
                        char c = args.charAt(i);
                        if (c == '`' && (i == 0 || args.charAt(i-1) != '\\')) {
                            inBackticks = !inBackticks;
                        } else if (c == ',' && !inBackticks) {
                            commaIndex = i;
                            break;
                        }
                    }
                    
                    if (commaIndex > 0) {
                        String column = args.substring(0, commaIndex).trim();
                        String patternPart = args.substring(commaIndex + 1).trim();
                        
                        // Remove _utf8mb4 prefix if present
                        String regexString = patternPart;
                        if (regexString.toLowerCase().startsWith("_utf8mb4")) {
                            regexString = regexString.substring(8).trim();
                        }
                        
                        // Handle escaped quotes: \' at start and end should become '
                        if (regexString.startsWith("\\'")) {
                            regexString = "'" + regexString.substring(2);
                        }
                        if (regexString.endsWith("\\'")) {
                            regexString = regexString.substring(0, regexString.length() - 2) + "'";
                        }
                        
                        // Unescape double backslashes to single backslashes
                        // MySQL stores \\\\ as the representation of \\ in the pattern
                        regexString = regexString.replace("\\\\\\\\", "\\\\");
                        
                        // Build the REGEXP syntax
                        return column + " REGEXP " + regexString;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to convert regexp_like format, using original: {}", checkClause, e);
            }
        }
        
        // Return as-is if not regexp_like format or conversion failed
        return checkClause;
    }

    @Override
    public void close() throws Exception {
    }
}
