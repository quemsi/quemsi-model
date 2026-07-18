package com.quemsi.model.flow.db.mysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import com.quemsi.model.flow.db.sql.DbView;
import com.quemsi.model.flow.db.sql.diff.DbCheckConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbColumnDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbForeignKeyDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbModelDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbUniqueConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbViewDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffEntityType;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServiceMysql implements DDLService{
    private DataSource dataSource;

	/** MySQL identifier quoting; escape ` as ``. */
	private static void appendBacktickQuoted(StringBuilder sb, String name) {
		sb.append('`').append(name.replace("`", "``")).append('`');
	}

	private static String backtickQuoted(String name) {
		StringBuilder sb = new StringBuilder();
		appendBacktickQuoted(sb, name);
		return sb.toString();
	}

	/** Builds a single multi-table DROP; returns null when there are no names. */
	static String buildMultiTableDropSql(String... tableNames) {
		if (tableNames == null || tableNames.length == 0) {
			return null;
		}
		return "DROP TABLE IF EXISTS " + String.join(", ", tableNames);
	}

	/** Strip trailing semicolons so rewriteBatchedStatements does not produce `;;`. */
	static String stripTrailingSemicolon(String sql) {
		if (sql == null) {
			return null;
		}
		String trimmed = sql.trim();
		while (trimmed.endsWith(";")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return trimmed;
	}
    
    @Override
	public boolean dropTables(String... tableNames) {
		String dropSql = buildMultiTableDropSql(tableNames);
		if (dropSql == null) {
			return true;
		}
		try (Connection conn = dataSource.getConnection();
			 Statement s = conn.createStatement()) {
			try {
				s.execute("SET FOREIGN_KEY_CHECKS=0");
				log.info("drop tables sql :{}", dropSql);
				s.executeUpdate(dropSql);
			} finally {
				try {
					s.execute("SET FOREIGN_KEY_CHECKS=1");
				} catch (SQLException restoreEx) {
					log.warn("failed to restore FOREIGN_KEY_CHECKS after dropTables", restoreEx);
				}
			}
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
	}

    @Override
    public boolean dropSequences(String... sequenceNames) {
        log.info("drop sequences: {}", Arrays.toString(sequenceNames));
        return true;
    }

    @Override
    public boolean dropViews(String... viewNames) {
        try (Connection conn = dataSource.getConnection()) {
            Statement s = conn.createStatement();
            for (String viewName : viewNames) {
				/* No trailing ';' — rewriteBatchedStatements joins batch entries with ';' */
                s.addBatch("DROP VIEW IF EXISTS " + viewName);
            }
            s.executeBatch();
            return true;
        } catch (Exception e) {
            throw Exceptions.server("failed-to-drop-views").withCause(e).get();
        }
    }

    public void disableConstraints(Set<ReferenceInfo> constraints){
		if (constraints == null || constraints.isEmpty()) {
			return;
		}
		try (Connection conn = dataSource.getConnection();
			 Statement s = conn.createStatement()) {
			for (ReferenceInfo refInfo : constraints) {
				String dropConstraintSql = "ALTER TABLE " + refInfo.getSrcTableName()
					+ " DROP FOREIGN KEY " + backtickQuoted(refInfo.getConstraintName());
				log.info("drop constraint sql :{}", dropConstraintSql);
				s.addBatch(dropConstraintSql);
			}
			try {
				s.executeBatch();
			} catch (SQLException ignore) {
				log.info("ignored disable constraints batch", ignore);
			}
		} catch (SQLException e) {
			log.info("ignored disable constraints", e);
		}
	}

    @Override
    public void enableContraints(Set<ReferenceInfo> constraints){
		if (constraints == null || constraints.isEmpty()) {
			return;
		}
		try (Connection conn = dataSource.getConnection();
			 Statement s = conn.createStatement()) {
			try {
				s.execute("SET FOREIGN_KEY_CHECKS=0");
				for (ReferenceInfo refInfo : constraints) {
					String enableConstraintSql = stripTrailingSemicolon(generateAddForeignKeySql(refInfo));
					log.info("enable constraint sql :{}", enableConstraintSql);
					s.addBatch(enableConstraintSql);
				}
				try {
					s.executeBatch();
				} catch (SQLException ignore) {
					log.info("ignored enable constraints batch", ignore);
				}
			} finally {
				try {
					s.execute("SET FOREIGN_KEY_CHECKS=1");
				} catch (SQLException restoreEx) {
					log.warn("failed to restore FOREIGN_KEY_CHECKS after enableContraints", restoreEx);
				}
			}
		} catch (SQLException e) {
			log.info("ignored enable constraints", e);
		}
	}

    @Override
	public void createTables(DbModel dbModel) {
		LinkedList<StringBuilder> scripts = new LinkedList<>();
		Map<String, ContraintInfo> constraintInfos = dbModel.getContraintInfos().stream().collect(Collectors.toMap(ContraintInfo::getConstraintName, Function.identity()));
		/* FKs are applied after data load via enableContraints — omit from CREATE for faster DDL */
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
                    if(c.getColumnType().toUpperCase().contains("VARCHAR")){
                        sb.append(" DEFAULT '" + c.getColumnDefault() + "'");
                    } else {
                        sb.append(" DEFAULT " + c.getColumnDefault());
                    }
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
			sb.append(System.lineSeparator()).append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
			log.info("create script for {} : {}", tableName, sb.toString());
			scripts.add(sb);
		}
		for(ContraintInfo contraintInfo : dbModel.getContraintInfos()){
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(contraintInfo.getTableName()).append(" ADD CONSTRAINT ");
			appendBacktickQuoted(sb, contraintInfo.getConstraintName());
			sb.append(" UNIQUE").append(" (");
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
			appendBacktickQuoted(sb, checkConstraint.getConstraintName());
			String convertedCondef = convertCheckClause(checkConstraint.getCondef());
			sb.append(" CHECK (").append(convertedCondef).append(");");
			log.info("create check constraint {} for table {} : {}", checkConstraint.getConstraintName(), checkConstraint.getTableName(), sb.toString());
			scripts.add(sb);
		}
		if (scripts.isEmpty()) {
			return;
		}
		try (Connection conn = dataSource.getConnection();
			 Statement s = conn.createStatement()) {
			for (StringBuilder sb : scripts) {
				s.addBatch(stripTrailingSemicolon(sb.toString()));
			}
			log.info("create tables batch size {}", scripts.size());
			s.executeBatch();
		} catch (SQLException ignore) {
			log.info("create tables sql", ignore);
		}
	}

	@Override
	public void createViews(DbModel dbModel) {
		if (dbModel.getViews() == null || dbModel.getViews().isEmpty()) {
			return;
		}
		LinkedList<DbView> ordered = dbModel.orderedViews();
		LinkedList<String> reverseNames = new LinkedList<>();
		for (DbView view : ordered) {
			reverseNames.addFirst(view.qualifiedName());
		}
		dropViews(reverseNames.toArray(new String[0]));
		try (Connection conn = dataSource.getConnection()) {
			Statement s = conn.createStatement();
			for (DbView view : ordered) {
				String sql = createViewSql(view);
				log.info("ddl : {}", sql);
				s.executeUpdate(sql);
			}
		} catch (SQLException e) {
			throw Exceptions.server("failed-to-create-views").withCause(e).get();
		}
	}

	@Override
	public void createFunctions(DbModel dbModel) {
		// MySQL routines not included in view backup yet
	}

	static String dropViewSql(String qualifiedName) {
		return "DROP VIEW IF EXISTS " + qualifiedName + ";";
	}

	static String createViewSql(DbView view) {
		String def = view.getDefinition();
		return "CREATE VIEW " + view.qualifiedName() + " AS " + def
			+ (def != null && def.trim().endsWith(";") ? "" : ";");
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

    /**
     * Converts a DbModelDiff to a list of MySQL SQL statements.
     * Note: MySQL does not support sequences, so sequence operations are ignored.
     * 
     * @param diff The database model diff containing operations to convert
     * @return List of SQL statements as strings
     */
    @Override
    public List<String> ddlFrom(DbModelDiff diff, DbModel dbModel) {
        List<String> statements = new ArrayList<>();
        
        if (diff == null || diff.getOperations() == null || diff.getOperations().isEmpty()) {
            return statements;
        }

        List<String> viewDrops = new ArrayList<>();
        List<String> viewCreates = new ArrayList<>();
        List<String> other = new ArrayList<>();

        for (DbModelDiffOp operation : diff.getOperations()) {
            if (operation.getEntityType() == DiffEntityType.SEQUENCE) {
                continue;
            }
            if (operation.getEntityType() == DiffEntityType.VIEW) {
                List<String> viewSql = generateViewSql((DbViewDiffOp) operation, operation.getOpType());
                for (String sql : viewSql) {
                    if (sql.regionMatches(true, 0, "DROP VIEW", 0, 9)) {
                        viewDrops.add(sql);
                    } else {
                        viewCreates.add(sql);
                    }
                }
            } else {
                other.addAll(generateSqlForOperation(operation));
            }
        }

        statements.addAll(viewDrops);
        statements.addAll(other);
        statements.addAll(viewCreates);
        return statements;
    }
    
    private List<String> generateSqlForOperation(DbModelDiffOp operation) {
        List<String> statements = new ArrayList<>();
        
        DiffEntityType entityType = operation.getEntityType();
        DiffOpType opType = operation.getOpType();
        
        switch (entityType) {
            case TABLE:
                statements.addAll(generateTableSql((DbTableDiffOp) operation, opType));
                break;
            case COLUMN:
                statements.addAll(generateColumnSql((DbColumnDiffOp) operation, opType));
                break;
            case FOREIGN_KEY:
                statements.addAll(generateForeignKeySql((DbForeignKeyDiffOp) operation, opType));
                break;
            case UNIQUE_CONSTRAINT:
                statements.addAll(generateUniqueConstraintSql((DbUniqueConstraintDiffOp) operation, opType));
                break;
            case CHECK_CONSTRAINT:
                statements.addAll(generateCheckConstraintSql((DbCheckConstraintDiffOp) operation, opType));
                break;
            case INDEX:
                statements.addAll(generateIndexSql((DbIndexDiffOp) operation, opType));
                break;
            case SEQUENCE:
                // MySQL doesn't support sequences, skip
                break;
            case VIEW:
                statements.addAll(generateViewSql((DbViewDiffOp) operation, opType));
                break;
        }
        
        return statements;
    }

    private List<String> generateViewSql(DbViewDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        switch (opType) {
            case CREATE:
                if (operation.getNewView() != null) {
                    statements.add(createViewSql(operation.getNewView()));
                }
                break;
            case DROP:
                statements.add(dropViewSql(operation.getQualifiedName()));
                break;
            case MODIFY:
                statements.add(dropViewSql(operation.getQualifiedName()));
                if (operation.getNewView() != null) {
                    statements.add(createViewSql(operation.getNewView()));
                }
                break;
        }
        return statements;
    }
    
    private List<String> generateTableSql(DbTableDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewTable() != null) {
                    statements.add(generateCreateTableSql(operation.getNewTable()));
                }
                break;
            case DROP:
                if (operation.getOldTable() != null) {
                    statements.add("DROP TABLE IF EXISTS " + operation.getQualifiedName() + ";");
                }
                break;
            case MODIFY:
                // For MODIFY, we drop and recreate the table
                if (operation.getOldTable() != null) {
                    statements.add("DROP TABLE IF EXISTS " + operation.getQualifiedName() + ";");
                }
                if (operation.getNewTable() != null) {
                    statements.add(generateCreateTableSql(operation.getNewTable()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateCreateTableSql(DbTable table) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table.qualifiedName()).append(" (").append(System.lineSeparator());
        DbColumn[] columns = table.orderedColumns();
        int index = 0;
        
        for (DbColumn c : columns) {
            sb.append("  `").append(c.getName()).append("` ").append(c.getColumnType());
            
            if (!c.isNullable()) {
                sb.append(" NOT NULL");
            }
            
            if (c.getColumnDefault() == null) {
                if (c.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT").contains(c.getColumnType().toUpperCase())) {
                    sb.append(" DEFAULT NULL");
                }
            } else {
                String defValue = c.getColumnDefault();
                if(c.getColumnType().toUpperCase().contains("VARCHAR")){
                    defValue = "'" + defValue + "'";
                }
                sb.append(" DEFAULT ").append(defValue);
            }
            
            if (index < columns.length - 1) {
                sb.append(",").append(System.lineSeparator());
            }
            index++;
        }
        
        if (table.getPkColumnNames().size() > 0) {
            sb.append(",").append(System.lineSeparator());
            sb.append("  PRIMARY KEY (");
            Iterator<String> cIt = table.getPkColumnNames().iterator();
            while (cIt.hasNext()) {
                String cName = cIt.next();
                sb.append("`").append(cName).append("`");
                if (cIt.hasNext()) {
                    sb.append(", ");
                }
            }
            sb.append(")");
        }
        
        sb.append(System.lineSeparator()).append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
        return sb.toString();
    }
    
    private List<String> generateColumnSql(DbColumnDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        String tableName = operation.getTableQualifiedName();
        String columnName = operation.getColumnName();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewColumn() != null) {
                    statements.add(generateAddColumnSql(tableName, operation.getNewColumn()));
                }
                break;
            case DROP:
                statements.add("ALTER TABLE " + tableName + " DROP COLUMN `" + columnName + "`;");
                break;
            case MODIFY:
                if (operation.getOldColumn() != null && operation.getNewColumn() != null) {
                    statements.addAll(generateModifyColumnSql(tableName, operation.getOldColumn(), operation.getNewColumn()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateAddColumnSql(String tableName, DbColumn column) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD COLUMN `").append(column.getName()).append("` ");
        sb.append(column.getColumnType());
        
        if (!column.isNullable()) {
            sb.append(" NOT NULL");
        }
        
        if (column.getColumnDefault() != null) {
            sb.append(" DEFAULT ").append(column.getColumnDefault());
        } else if (column.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT").contains(column.getColumnType().toUpperCase())) {
            sb.append(" DEFAULT NULL");
        }
        
        sb.append(";");
        return sb.toString();
    }
    
    private List<String> generateModifyColumnSql(String tableName, DbColumn oldColumn, DbColumn newColumn) {
        List<String> statements = new ArrayList<>();
        
        // MySQL uses MODIFY COLUMN for all changes
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName)
            .append(" MODIFY COLUMN `").append(newColumn.getName()).append("` ")
            .append(newColumn.getColumnType());
        
        if (!newColumn.isNullable()) {
            sb.append(" NOT NULL");
        }
        
        if (newColumn.getColumnDefault() != null) {
            sb.append(" DEFAULT ");
            String dataType = newColumn.getColumnType().toUpperCase();
            if(dataType.contains("VARCHAR")){
                sb.append(  "'").append(newColumn.getColumnDefault()).append("'");
            } else {
                sb.append(newColumn.getColumnDefault());
            }
        } else if (newColumn.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT").contains(newColumn.getColumnType().toUpperCase())) {
            sb.append(" DEFAULT NULL");
        }
        
        sb.append(";");
        statements.add(sb.toString());
        
        return statements;
    }
    
    private List<String> generateForeignKeySql(DbForeignKeyDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewReference() != null) {
                    statements.add(generateAddForeignKeySql(operation.getNewReference()));
                }
                break;
            case DROP:
                if (operation.getOldReference() != null) {
                    ReferenceInfo ref = operation.getOldReference();
                    statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP FOREIGN KEY " + backtickQuoted(ref.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldReference() != null) {
                    ReferenceInfo ref = operation.getOldReference();
                    statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP FOREIGN KEY " + backtickQuoted(ref.getConstraintName()) + ";");
                }
                if (operation.getNewReference() != null) {
                    statements.add(generateAddForeignKeySql(operation.getNewReference()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateAddForeignKeySql(ReferenceInfo ref) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(ref.srcQualifiedName())
            .append(" ADD CONSTRAINT ");
        appendBacktickQuoted(sb, ref.getConstraintName());
        sb.append(" FOREIGN KEY (");
        
        Iterator<String> cIt = ref.getSrcColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("`").append(cName).append("`");
            if (cIt.hasNext()) {
                sb.append(", ");
            }
        }
        
        sb.append(") REFERENCES ").append(ref.refQualifiedName()).append(" (");
        cIt = ref.getRefColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("`").append(cName).append("`");
            if (cIt.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append(");");
        
        return sb.toString();
    }
    
    private List<String> generateUniqueConstraintSql(DbUniqueConstraintDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewConstraint() != null) {
                    statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
                }
                break;
            case DROP:
                if (operation.getOldConstraint() != null) {
                    ContraintInfo constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP INDEX " + backtickQuoted(constraint.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    ContraintInfo constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP INDEX " + backtickQuoted(constraint.getConstraintName()) + ";");
                }
                if (operation.getNewConstraint() != null) {
                    statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateAddUniqueConstraintSql(ContraintInfo constraint) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(constraint.qualifiedTableName())
            .append(" ADD CONSTRAINT ");
        appendBacktickQuoted(sb, constraint.getConstraintName());
        sb.append(" UNIQUE (");
        
        Iterator<String> cIt = constraint.getColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("`").append(cName).append("`");
            if (cIt.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append(");");
        
        return sb.toString();
    }
    
    private List<String> generateCheckConstraintSql(DbCheckConstraintDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewConstraint() != null) {
                    CheckConstraint constraint = operation.getNewConstraint();
                    String tableName = constraint.getTableName();
                    StringBuilder sb = new StringBuilder("ALTER TABLE ");
                    if (tableName.contains(".") || tableName.contains("`")) {
                        sb.append("`").append(tableName.replace("`", "``")).append("`");
                    } else {
                        sb.append(tableName);
                    }
                    sb.append(" ADD CONSTRAINT ");
                    appendBacktickQuoted(sb, constraint.getConstraintName());
                    String convertedCondef = convertCheckClause(constraint.getCondef());
                    sb.append(" CHECK (").append(convertedCondef).append(");");
                    statements.add(sb.toString());
                }
                break;
            case DROP:
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + backtickQuoted(constraint.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + backtickQuoted(constraint.getConstraintName()) + ";");
                }
                if (operation.getNewConstraint() != null) {
                    CheckConstraint constraint = operation.getNewConstraint();
                    String tableName = constraint.getTableName();
                    StringBuilder sb = new StringBuilder("ALTER TABLE ");
                    if (tableName.contains(".") || tableName.contains("`")) {
                        sb.append("`").append(tableName.replace("`", "``")).append("`");
                    } else {
                        sb.append(tableName);
                    }
                    sb.append(" ADD CONSTRAINT ");
                    appendBacktickQuoted(sb, constraint.getConstraintName());
                    String convertedCondef = convertCheckClause(constraint.getCondef());
                    sb.append(" CHECK (").append(convertedCondef).append(");");
                    statements.add(sb.toString());
                }
                break;
        }
        
        return statements;
    }
    
    private List<String> generateIndexSql(DbIndexDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewIndex() != null) {
                    statements.add(generateCreateIndexSql(operation.getNewIndex(), operation.getTableQualifiedName()));
                }
                break;
            case DROP:
                if (operation.getOldIndex() != null) {
                    IndexInfo index = operation.getOldIndex();
                    statements.add("DROP INDEX " + index.getIndexName() + " ON " + operation.getTableQualifiedName() + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldIndex() != null) {
                    IndexInfo index = operation.getOldIndex();
                    statements.add("DROP INDEX " + index.getIndexName() + " ON " + operation.getTableQualifiedName() + ";");
                }
                if (operation.getNewIndex() != null) {
                    statements.add(generateCreateIndexSql(operation.getNewIndex(), operation.getTableQualifiedName()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateCreateIndexSql(IndexInfo index, String tableQualifiedName) {
        StringBuilder sb = new StringBuilder("CREATE ");
        if (index.isUnique()) {
            sb.append("UNIQUE ");
        }
        sb.append("INDEX ").append(index.getIndexName()).append(" ON ").append(tableQualifiedName).append(" (");
        
        Iterator<String> icIt = index.getColumns().iterator();
        while (icIt.hasNext()) {
            String ic = icIt.next();
            sb.append("`").append(ic).append("`");
            if (icIt.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append(");");
        
        return sb.toString();
    }
    
    @Override
    public void executeSql(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            Statement s = conn.createStatement();
            s.execute(sql);
            log.debug("Executed SQL: {}", sql);
        } catch (SQLException e) {
            log.error("Failed to execute SQL: {}", sql, e);
            throw e;
        }
    }
}
