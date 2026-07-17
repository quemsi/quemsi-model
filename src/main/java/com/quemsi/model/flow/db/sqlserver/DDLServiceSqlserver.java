package com.quemsi.model.flow.db.sqlserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.CommonOps;
import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.util.CommonHelpers;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbView;
import com.quemsi.model.flow.db.sql.diff.DbCheckConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbColumnDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbForeignKeyDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbModelDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbSequenceDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbUniqueConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbViewDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffEntityType;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;

import java.util.ArrayList;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServiceSqlserver implements DDLService{
    private Connection conn;

    @Override
    public boolean dropTables(String... tableNames) {
        try{
			Statement s = conn.createStatement();
			for(String tableName : tableNames){
				s.addBatch("DROP TABLE IF EXISTS " + tableName);
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
		try{
			Statement s = conn.createStatement();
			for(String sequenceName : sequenceNames){
				s.addBatch("DROP SEQUENCE IF EXISTS " + sequenceName);
			}
			s.executeBatch();
			return true;
		}catch(Exception e){
			e.printStackTrace();
			throw Exceptions.server("failed-to-clear-sequences").withCause(e).get();
		}
	}

	@Override
	public boolean dropViews(String... viewNames) {
		try {
			Statement s = conn.createStatement();
			for (String viewName : viewNames) {
				s.addBatch("DROP VIEW IF EXISTS " + viewName);
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-drop-views").withCause(e).get();
		}
	}

	public LinkedList<String> tables(Set<String> schemas){
		try(
			PreparedStatement ps = conn.prepareStatement(CommonHelpers.addInParameter(DatasourceFactorySqlserver.SQL_FOR_TABLES, schemas.size()));
		){
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			LinkedList<String> tables = new LinkedList<>();
			while(rs.next()){
				String schemaName = rs.getString("SCHEMA_NAME");
				String tableName = rs.getString("TABLE_NAME");
				tables.add(CommonHelpers.qualifiedName(schemaName, tableName));
			}
			return tables;
		}catch(Exception e){
			throw Exceptions.server("unable-to-get-tables").withCause(e).get();
		}
	}

	public LinkedList<String> sequences(Set<String> schemas){
		try(
			PreparedStatement ps = conn.prepareStatement(CommonHelpers.addInParameter(DatasourceFactorySqlserver.SQL_FOR_SEQUENCES, schemas.size()));
		){
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			LinkedList<String> seqs = new LinkedList<>();
			while(rs.next()){
				String schemaName = rs.getString("SCHEMA_NAME");
				String tableName = rs.getString("SEQUENCE_NAME");
				seqs.add(CommonHelpers.qualifiedName(schemaName, tableName));
			}
			return seqs;
		}catch(Exception e){
			throw Exceptions.server("unable-to-get-tables").withCause(e).get();
		}
	}

    @Override
    public void disableConstraints(Set<ReferenceInfo> constraints) {
        for(ReferenceInfo refInfo : constraints) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ");
            sb.append(refInfo.getSrcSchema()) .append(refInfo.getSrcTableName()).append("NOCHECK CONSTRAINT ");
            appendBracketQuoted(sb, refInfo.getConstraintName());
            sb.append(";");
            try{
                String dropConstraintSql = sb.toString();
                log.info("disable constraint sql :{}", dropConstraintSql);
                Statement s = conn.createStatement();
                s.executeUpdate(dropConstraintSql);
            }catch(SQLException ignore){
                log.info("ignored disable constraint " + refInfo.getConstraintName(), ignore);
            }
        }
    }

    @Override
    public void enableContraints(Set<ReferenceInfo> constraints) {
        for(ReferenceInfo refInfo : constraints) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ");
            sb.append(refInfo.srcQualifiedName()).append(" WITH CHECK CHECK CONSTRAINT ");
            appendBracketQuoted(sb, refInfo.getConstraintName());
            sb.append(";");
            try{
                String dropConstraintSql = sb.toString();
                log.info("enable constraint sql :{}", dropConstraintSql);
                Statement s = conn.createStatement();
                s.executeUpdate(dropConstraintSql);
            }catch(SQLException ignore){
                log.info("ignored enable constraint " + refInfo.getConstraintName(), ignore);
            }
        }
    }

    private String columnType(String type, Integer maxLength, Integer precision, Integer scale){
        if("varchar".equals(type) && maxLength != null){
            return new StringBuffer(type).append("(").append(maxLength).append(")").toString();
        } else if(Set.of("varbinary", "nvarchar", "nchar").contains(type)){
            StringBuilder sb = new StringBuilder(type);
            if(maxLength != null){
                sb.append("(");
                if(maxLength == -1){
                    sb.append("MAX");
                }else{
                    sb.append(maxLength / 2);
                }
                sb.append(")");
                return sb.toString();
            }
        } else if(Set.of("decimal", "numeric").contains(type)){
            return new StringBuffer(type).append("(").append(precision).append(",").append(scale).append(")").toString();
        }
        return type;
    }
    private StringBuilder escape(StringBuilder sb, String columnName){
		if(DatasourceFactorySqlserver.RESERVED_KEYS.contains(columnName.toUpperCase())){
			sb.append("[").append(columnName).append("]");
		}else{
			sb.append(columnName);
		}
		return sb;
	}

	/** T-SQL bracket identifier; escape ] as ]]. */
	private static void appendBracketQuoted(StringBuilder sb, String name) {
		sb.append('[').append(name.replace("]", "]]")).append(']');
	}

	private static String bracketQuoted(String name) {
		StringBuilder sb = new StringBuilder();
		appendBracketQuoted(sb, name);
		return sb.toString();
	}

    @Override
    public void createTables(DbModel dbModel) {
        LinkedList<StringBuilder> scripts = new LinkedList<>();
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream().collect(Collectors.groupingBy(r -> r.srcQualifiedName()));
		Set<String> existingTables = new HashSet<>(tables(dbModel.getSchemas()));
		Set<String> sequences = new HashSet<>(sequences(dbModel.getSchemas()));
		if(!dbModel.getSequences().isEmpty()){
			for(DbSequence seq : dbModel.getSequences()){
				if(sequences.contains(seq.qualifiedName())){
					continue;
				}
				StringBuilder seqBuilder = new StringBuilder("CREATE SEQUENCE ");
				seqBuilder.append(seq.getSchema()).append(".").append(seq.getName());
				seqBuilder.append(" START WITH ");
				if(seq.getLastValue() == null){
					seqBuilder.append(seq.getStartValue());
				}else{
					seqBuilder.append(seq.getLastValue() + 1L);
				}
				seqBuilder.append(" INCREMENT BY ").append(seq.getIncrementBy());
				if(seq.getMinValue() != null && seq.getMinValue() > Long.MIN_VALUE){
					seqBuilder.append(" MINVALUE ").append(seq.getMinValue());
				}else{
					seqBuilder.append(" NO MINVALUE");
				}
				if(seq.getMaxValue() != null && seq.getMaxValue() < Long.MAX_VALUE){
					seqBuilder.append(" MAXVALUE ").append(seq.getMaxValue());
				}else{
					seqBuilder.append(" NO MAXVALUE");
				}
				if(seq.isCycle()){
					seqBuilder.append(" CYCLE");
				}else{
					seqBuilder.append(" NO CYCLE");
				}
				if(seq.getCacheSize() != null && seq.getCacheSize() > 0L){
					seqBuilder.append(" CACHE ").append(seq.getCacheSize());
				}else{
					seqBuilder.append(" NO CACHE");
				}
				seqBuilder.append(";");

				log.info("sequence sql : {}", seqBuilder);
				scripts.add(seqBuilder);
			}
		}
		for(String tableName : dbModel.orderedTableNames()){
			if(existingTables.contains(tableName)){
				log.info("table {} already exists in schema {} skipping", tableName, dbModel.getSchemas());
				continue;
			}
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			boolean hasClustedIndex = CommonOps.getOrDefault(dbModel.getIndexes(), tableName, () -> new HashMap<>())
				.values().stream().map(ii -> "CLUSTERED".equals(ii.getIndexType())).reduce(Boolean.FALSE, (a, v) -> a || v);
			StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			for(DbColumn c : columns){
				sb.append("  [");
				escape(sb, c.getName()).append("] ").append(columnType(c.getDataType(), c.getMaxLength(), c.getNumPrecision(), c.getNumScale()));
                if(c.isIdentity()){
                    sb.append(" IDENTITY(1,1)");
                }
				if(c.getColumnDefault() != null){
                    sb.append(" DEFAULT " + StringUtils.trimSymetric(c.getColumnDefault(), "(", ")"));
                }
                if(!c.isNullable()){
					sb.append(" NOT NULL");
				} else if(c.getColumnDefault() == null){
                    // if(c.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT"
                    //     ,"XML", "VARBINARY", "NVARCHAR").contains(c.getColumnType().toUpperCase())){
					// 	sb.append(" DEFAULT NULL");
					// }
                }
				sb.append(",").append(System.lineSeparator());
			}
			if(table.getPkColumnNames().size() > 0){
				sb.append("  CONSTRAINT ");
				appendBracketQuoted(sb, table.getPkConstraintName());
				sb.append(" PRIMARY KEY ");
				if(hasClustedIndex){
					sb.append("NONCLUSTERED ");
				}
				sb.append("(");
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
			if(tableReferences.containsKey(tableName)){
				Iterator<ReferenceInfo> refIt = tableReferences.get(tableName).iterator();
				while(refIt.hasNext()){
					ReferenceInfo ref = refIt.next();
					if(dbModel.getCircularIgnore() != null && dbModel.getCircularIgnore().contains(ref)){
						continue;
					}
					sb.append(",").append(System.lineSeparator())
						.append("  CONSTRAINT ");
					appendBracketQuoted(sb, ref.getConstraintName());
					sb.append(" FOREIGN KEY (");
						Iterator<String> cIt = ref.getSrcColumnNames().iterator();
						while(cIt.hasNext()){
							String cName = cIt.next();
							sb.append(cName);
							if(cIt.hasNext()){
								sb.append(", ");
							}
						}
						sb.append(") REFERENCES ")
						.append(ref.getRefSchema()).append(".").append(ref.getRefTableName()).append(" (");
						cIt = ref.getRefColumnNames().iterator();
						while(cIt.hasNext()){
							String cName = cIt.next();
							sb.append(cName);
							if(cIt.hasNext()){
								sb.append(", ");
							}
						}
						sb.append(")");
						;
				}
			}
			sb.append(System.lineSeparator()).append(");");
			log.info("create script for {} : {}", tableName, sb.toString());
			scripts.add(sb);
			if(dbModel.getIndexes().containsKey(tableName)){
				Map<String, IndexInfo> indexes = dbModel.getIndexes().get(tableName);
				Iterator<String> indNameIt = indexes.keySet().iterator();
				while(indNameIt.hasNext()){
					boolean withRowguid = false;
					String indName = indNameIt.next();
					StringBuilder indBuilder = new StringBuilder("CREATE ");
					IndexInfo indCols = indexes.get(indName);
					if(indCols.isUnique()){
						indBuilder.append("UNIQUE");
					}
					if("XML".equals(indCols.getIndexType())){
						indBuilder.append(" PRIMARY");
					}
					indBuilder.append(" ").append(indCols.getIndexType());
					indBuilder.append(" INDEX ").append(indCols.getIndexName()).append(" ON ");
					indBuilder.append(tableName);
					indBuilder.append(" (");
					Iterator<String> icIt = indCols.getColumns().iterator();
					while(icIt.hasNext()){
						String ic = icIt.next();
						if("rowguid".equals(ic)){
							withRowguid = true;
						}
						indBuilder.append("[").append(ic).append("]");
						if(icIt.hasNext()){
							indBuilder.append(", ");
						}
					}
					indBuilder.append(")");
					if(!indCols.getExtraColumns().isEmpty()){
						StringBuilder includeBuilder = new StringBuilder(" INCLUDE (");
						Iterator<String> xcIt = indCols.getExtraColumns().iterator();
						while(xcIt.hasNext()){
							String xc = xcIt.next();
							includeBuilder.append(xc);
							if(icIt.hasNext()){
								includeBuilder.append(", ");
							}
						}
						includeBuilder.append(")");
						indBuilder.append(includeBuilder.toString());
					}
					indBuilder.append(";");
					if(!withRowguid){
						log.info("index sql : {}", indBuilder);
						scripts.add(indBuilder);
					}
				};
			}
		}
		if(dbModel.getCircularIgnore() != null){
			for(ReferenceInfo ref : dbModel.getCircularIgnore()){
				if(existingTables.contains(ref.srcQualifiedName())){
					log.info("circular FK {} already exists on {} skipping", ref.getConstraintName(), ref.srcQualifiedName());
					continue;
				}
				scripts.add(new StringBuilder(generateAddForeignKeySql(ref)));
			}
		}
		for(ContraintInfo contraintInfo : dbModel.getContraintInfos()){
			if(existingTables.contains(contraintInfo.qualifiedTableName())){
				log.info("unique constraint {} already exists on {} skipping", contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName());
				continue;
			}
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(contraintInfo.qualifiedTableName()).append(" ADD CONSTRAINT ");
			appendBracketQuoted(sb, contraintInfo.getConstraintName());
			sb.append(" UNIQUE").append(" (");
			Iterator<String> cIt = contraintInfo.getColumnNames().iterator();
			while(cIt.hasNext()){
				String cName = cIt.next();
				sb.append("[").append(cName).append("]");
				if(cIt.hasNext()){
					sb.append(", ");
				}
			}
			sb.append(");");
			log.info("create unique constraint {} for table {} : {}", contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName(), sb.toString());
			scripts.add(sb);
		}
		for(CheckConstraint checkConstraint : dbModel.getCheckConstraints()){
			if(existingTables.contains(checkConstraint.qualifiedTableName())){
				log.info("check constraint {} already exists on {} skipping", checkConstraint.getConstraintName(), checkConstraint.qualifiedTableName());
				continue;
			}
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(checkConstraint.qualifiedTableName()).append(" WITH CHECK ADD CONSTRAINT ");
			appendBracketQuoted(sb, checkConstraint.getConstraintName());
			sb.append(" CHECK ").append(checkConstraint.getCondef()).append(";");
			log.info("create check constraint {} for table {} : {}", checkConstraint.getConstraintName(), checkConstraint.qualifiedTableName(), sb.toString());
			scripts.add(sb);
		}
		try{
			for(String schema : dbModel.getSchemas()){
				if(!checkSchema(schema)){
				StringBuilder csSql = new StringBuilder("create schema ").append(schema).append(";");
					Statement css = conn.createStatement();
					css.execute(csSql.toString());
				}
			}
			Statement s = conn.createStatement();
			for(StringBuilder sb : scripts){
				log.info("sql : {}", sb.toString());
				s.executeUpdate(sb.toString());
			}
		}catch(SQLException e){
			throw Exceptions.server("failed-to-create-tables").withCause(e).get();
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
		try {
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

	static String dropViewSql(String qualifiedName) {
		return "DROP VIEW IF EXISTS " + qualifiedName;
	}

	static String createViewSql(DbView view) {
		String def = view.getDefinition();
		String sql = "CREATE VIEW " + view.qualifiedName() + " AS " + def;
		if (def != null && def.trim().endsWith(";")) {
			return sql;
		}
		return sql + ";";
	}

	@Override
	public boolean checkSchema(String schema) throws SQLException{
		try(PreparedStatement ss = conn.prepareStatement(DatasourceFactorySqlserver.SQL_FOR_SCHEMA)){
			ss.setString(1, schema);
			ResultSet rss = ss.executeQuery();
			return rss.next();
		}
	}

    @Override
    public void close() throws Exception {
        if(conn != null){
            conn.close();
        }
    }

    /**
     * Converts a DbModelDiff to a list of SQL Server SQL statements.
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
                other.addAll(generateSqlForOperation(operation, dbModel));
            }
        }

        statements.addAll(viewDrops);
        statements.addAll(other);
        statements.addAll(viewCreates);
        return statements;
    }
    
    private List<String> generateSqlForOperation(DbModelDiffOp operation, DbModel dbModel) {
        List<String> statements = new ArrayList<>();
        
        DiffEntityType entityType = operation.getEntityType();
        DiffOpType opType = operation.getOpType();
        
        switch (entityType) {
            case TABLE:
                statements.addAll(generateTableSql((DbTableDiffOp) operation, opType, dbModel));
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
                statements.addAll(generateSequenceSql((DbSequenceDiffOp) operation, opType));
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
    
    private List<String> generateTableSql(DbTableDiffOp operation, DiffOpType opType, DbModel dbModel) {
        List<String> statements = new ArrayList<>();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewTable() != null) {
                    statements.add(generateCreateTableSql(operation.getNewTable(), dbModel));
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
                    statements.add(generateCreateTableSql(operation.getNewTable(), dbModel));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateCreateTableSql(DbTable table, DbModel dbModel) {
        String tableName = table.qualifiedName();
		boolean hasClustedIndex = CommonOps.getOrDefault(dbModel.getIndexes(), tableName, () -> new HashMap<>())
				.values().stream().map(ii -> "CLUSTERED".equals(ii.getIndexType())).reduce(Boolean.FALSE, (a, v) -> a || v);
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream().collect(Collectors.groupingBy(r -> r.srcQualifiedName()));
		
		StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (").append(System.lineSeparator());
		DbColumn[] columns = table.orderedColumns();
		for(DbColumn c : columns){
			sb.append("  [");
			escape(sb, c.getName()).append("] ").append(columnType(c.getDataType(), c.getMaxLength(), c.getNumPrecision(), c.getNumScale()));
			if(c.isIdentity()){
				sb.append(" IDENTITY(1,1)");
			}
			if(c.getColumnDefault() != null){
				sb.append(" DEFAULT " + StringUtils.trimSymetric(c.getColumnDefault(), "(", ")"));
			}
			if(!c.isNullable()){
				sb.append(" NOT NULL");
			} else if(c.getColumnDefault() == null){
				// if(c.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT"
				//     ,"XML", "VARBINARY", "NVARCHAR").contains(c.getColumnType().toUpperCase())){
				// 	sb.append(" DEFAULT NULL");
				// }
			}
			sb.append(",").append(System.lineSeparator());
		}
		if(table.getPkColumnNames().size() > 0){
			sb.append("  CONSTRAINT ");
			appendBracketQuoted(sb, table.getPkConstraintName());
			sb.append(" PRIMARY KEY ");
			if(hasClustedIndex){
				sb.append("NONCLUSTERED ");
			}
			sb.append("(");
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
		if(tableReferences.containsKey(tableName)){
			Iterator<ReferenceInfo> refIt = tableReferences.get(tableName).iterator();
			while(refIt.hasNext()){
				ReferenceInfo ref = refIt.next();
				sb.append(",").append(System.lineSeparator())
					.append("  CONSTRAINT ");
				appendBracketQuoted(sb, ref.getConstraintName());
				sb.append(" FOREIGN KEY (");
					Iterator<String> cIt = ref.getSrcColumnNames().iterator();
					while(cIt.hasNext()){
						String cName = cIt.next();
						sb.append(cName);
						if(cIt.hasNext()){
							sb.append(", ");
						}
					}
					sb.append(") REFERENCES ")
					.append(ref.getRefSchema()).append(".").append(ref.getRefTableName()).append(" (");
					cIt = ref.getRefColumnNames().iterator();
					while(cIt.hasNext()){
						String cName = cIt.next();
						sb.append(cName);
						if(cIt.hasNext()){
							sb.append(", ");
						}
					}
					sb.append(")");
					;
			}
		}
		sb.append(System.lineSeparator()).append(");");
		log.info("create script for {} : {}", tableName, sb.toString());
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
                statements.add("ALTER TABLE " + tableName + " DROP COLUMN [" + columnName + "];");
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
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD [");
        escape(sb, column.getName()).append("] ").append(columnType(column.getDataType(), column.getMaxLength(), column.getNumPrecision(), column.getNumScale()));
        
        if (column.isIdentity()) {
            sb.append(" IDENTITY(1,1)");
        }
        
        if (column.getColumnDefault() != null) {
            sb.append(" DEFAULT ").append(StringUtils.trimSymetric(column.getColumnDefault(), "(", ")"));
        }
        
        if (!column.isNullable()) {
            sb.append(" NOT NULL");
        } else {
            sb.append(" NULL");
        }
        
        sb.append(";");
        return sb.toString();
    }
    
    private List<String> generateModifyColumnSql(String tableName, DbColumn oldColumn, DbColumn newColumn) {
        List<String> statements = new ArrayList<>();
        
        // Type change
        if (!Objects.equals(oldColumn.getColumnType(), newColumn.getColumnType()) || 
            !Objects.equals(oldColumn.getMaxLength(), newColumn.getMaxLength())) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName)
                .append(" ALTER COLUMN [").append(newColumn.getName()).append("] ")
                .append(columnType(newColumn.getDataType(), newColumn.getMaxLength(), newColumn.getNumPrecision(), newColumn.getNumScale()));
            
            if (!newColumn.isNullable()) {
                sb.append(" NOT NULL");
            } else {
                sb.append(" NULL");
            }
            
            sb.append(";");
            statements.add(sb.toString());
        } else {
            // Only nullable change
            if (oldColumn.isNullable() != newColumn.isNullable()) {
                StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName)
                    .append(" ALTER COLUMN [").append(newColumn.getName()).append("] ");
                if (newColumn.isNullable()) {
                    sb.append("NULL");
                } else {
                    sb.append("NOT NULL");
                }
                sb.append(";");
                statements.add(sb.toString());
            }
        }
        // Default change (separate statement)
        if (!Objects.equals(oldColumn.getColumnDefault(), newColumn.getColumnDefault())) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName);
			if (newColumn.getColumnDefault() == null) {
                sb.append(" DROP CONSTRAINT ");
                appendBracketQuoted(sb, oldColumn.getDefaultConstraintName());
            } else {
                sb.append(" ADD DEFAULT ").append(StringUtils.trimSymetric(newColumn.getColumnDefault(), "(", ")")).append(" FOR [").append(newColumn.getName()).append("];");
            }
            sb.append(";");
            statements.add(sb.toString());
        }
        
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
                    statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP CONSTRAINT " + bracketQuoted(ref.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldReference() != null) {
                    ReferenceInfo ref = operation.getOldReference();
                    statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP CONSTRAINT " + bracketQuoted(ref.getConstraintName()) + ";");
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
        appendBracketQuoted(sb, ref.getConstraintName());
        sb.append(" FOREIGN KEY (");
        
        Iterator<String> cIt = ref.getSrcColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("[").append(cName).append("]");
            if (cIt.hasNext()) {
                sb.append(", ");
            }
        }
        
        sb.append(") REFERENCES ").append(ref.refQualifiedName()).append(" (");
        cIt = ref.getRefColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("[").append(cName).append("]");
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
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    ContraintInfo constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + ";");
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
        appendBracketQuoted(sb, constraint.getConstraintName());
        sb.append(" UNIQUE (");
        
        Iterator<String> cIt = constraint.getColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("[").append(cName).append("]");
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
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " WITH CHECK ADD CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + " CHECK " + constraint.getCondef() + ";");
                }
                break;
            case DROP:
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + ";");
                }
                if (operation.getNewConstraint() != null) {
                    CheckConstraint constraint = operation.getNewConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " WITH CHECK ADD CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + " CHECK " + constraint.getCondef() + ";");
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
                    String qualifiedIndexName = CommonHelpers.qualifiedName(index.getSchemaName(), index.getIndexName());
                    statements.add("DROP INDEX " + qualifiedIndexName + " ON " + operation.getTableQualifiedName() + ";");
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
        
        if ("XML".equals(index.getIndexType())) {
            sb.append("PRIMARY ");
        }
        
        sb.append(index.getIndexType()).append(" INDEX ").append(index.getIndexName())
            .append(" ON ").append(tableQualifiedName).append(" (");
        
        Iterator<String> icIt = index.getColumns().iterator();
        while (icIt.hasNext()) {
            String ic = icIt.next();
            sb.append("[").append(ic).append("]");
            if (icIt.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append(")");
        
        if (index.getExtraColumns() != null && !index.getExtraColumns().isEmpty()) {
            sb.append(" INCLUDE (");
            Iterator<String> xcIt = index.getExtraColumns().iterator();
            while (xcIt.hasNext()) {
                String xc = xcIt.next();
                sb.append("[").append(xc).append("]");
                if (xcIt.hasNext()) {
                    sb.append(", ");
                }
            }
            sb.append(")");
        }
        
        sb.append(";");
        return sb.toString();
    }
    
    private List<String> generateSequenceSql(DbSequenceDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewSequence() != null) {
                    statements.add(generateCreateSequenceSql(operation.getNewSequence()));
                }
                break;
            case DROP:
                if (operation.getOldSequence() != null) {
                    statements.add("DROP SEQUENCE IF EXISTS " + operation.getQualifiedName() + ";");
                }
                break;
            case MODIFY:
                if (operation.getOldSequence() != null && operation.getNewSequence() != null) {
                    statements.addAll(generateAlterSequenceSql(operation.getOldSequence(), operation.getNewSequence()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateCreateSequenceSql(DbSequence seq) {
        StringBuilder sb = new StringBuilder("CREATE SEQUENCE ");
        sb.append(seq.getSchema()).append(".").append(seq.getName());
        sb.append(" START WITH ");
        if (seq.getLastValue() == null) {
            sb.append(seq.getStartValue());
        } else {
            sb.append(seq.getLastValue() + 1L);
        }
        sb.append(" INCREMENT BY ").append(seq.getIncrementBy());
        
        if (seq.getMinValue() != null && seq.getMinValue() > Long.MIN_VALUE) {
            sb.append(" MINVALUE ").append(seq.getMinValue());
        } else {
            sb.append(" NO MINVALUE");
        }
        
        if (seq.getMaxValue() != null && seq.getMaxValue() < Long.MAX_VALUE) {
            sb.append(" MAXVALUE ").append(seq.getMaxValue());
        } else {
            sb.append(" NO MAXVALUE");
        }
        
        if (seq.isCycle()) {
            sb.append(" CYCLE");
        } else {
            sb.append(" NO CYCLE");
        }
        
        if (seq.getCacheSize() != null && seq.getCacheSize() > 0L) {
            sb.append(" CACHE ").append(seq.getCacheSize());
        } else {
            sb.append(" NO CACHE");
        }
        sb.append(";");
        
        return sb.toString();
    }
    
    private List<String> generateAlterSequenceSql(DbSequence oldSeq, DbSequence newSeq) {
        List<String> statements = new ArrayList<>();
        StringBuilder sb = new StringBuilder("ALTER SEQUENCE ").append(newSeq.qualifiedName());
        boolean hasChanges = false;
        
        if (!Objects.equals(oldSeq.getIncrementBy(), newSeq.getIncrementBy()) && newSeq.getIncrementBy() != null) {
            sb.append(" INCREMENT BY ").append(newSeq.getIncrementBy());
            hasChanges = true;
        }
        
        if (!Objects.equals(oldSeq.getMinValue(), newSeq.getMinValue())) {
            if (newSeq.getMinValue() != null && newSeq.getMinValue() > Long.MIN_VALUE) {
                sb.append(" MINVALUE ").append(newSeq.getMinValue());
            } else {
                sb.append(" NO MINVALUE");
            }
            hasChanges = true;
        }
        
        if (!Objects.equals(oldSeq.getMaxValue(), newSeq.getMaxValue())) {
            if (newSeq.getMaxValue() != null && newSeq.getMaxValue() < Long.MAX_VALUE) {
                sb.append(" MAXVALUE ").append(newSeq.getMaxValue());
            } else {
                sb.append(" NO MAXVALUE");
            }
            hasChanges = true;
        }
        
        if (!Objects.equals(oldSeq.getLastValue(), newSeq.getLastValue()) || 
            !Objects.equals(oldSeq.getStartValue(), newSeq.getStartValue())) {
            if (newSeq.getLastValue() != null) {
                sb.append(" RESTART WITH ").append(newSeq.getLastValue() + 1L);
            } else if (newSeq.getStartValue() != null) {
                sb.append(" RESTART WITH ").append(newSeq.getStartValue());
            }
            hasChanges = true;
        }
        
        if (!Objects.equals(oldSeq.getCacheSize(), newSeq.getCacheSize())) {
            if (newSeq.getCacheSize() != null && newSeq.getCacheSize() > 0L) {
                sb.append(" CACHE ").append(newSeq.getCacheSize());
            } else {
                sb.append(" NO CACHE");
            }
            hasChanges = true;
        }
        
        if (oldSeq.isCycle() != newSeq.isCycle()) {
            if (newSeq.isCycle()) {
                sb.append(" CYCLE");
            } else {
                sb.append(" NO CYCLE");
            }
            hasChanges = true;
        }
        
        if (hasChanges) {
            sb.append(";");
            statements.add(sb.toString());
        }
        
        return statements;
    }
    
    @Override
    public void executeSql(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }
        try {
            Statement s = conn.createStatement();
            s.execute(sql);
            log.debug("Executed SQL: {}", sql);
        } catch (SQLException e) {
            log.error("Failed to execute SQL: {}", sql, e);
            throw e;
        }
    }
}
