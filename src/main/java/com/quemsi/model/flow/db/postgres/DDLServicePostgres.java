package com.quemsi.model.flow.db.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.diff.DbCheckConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbColumnDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbForeignKeyDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbIndexDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbModelDiff;
import com.quemsi.model.flow.db.sql.diff.DbModelDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbSequenceDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbTableDiffOp;
import com.quemsi.model.flow.db.sql.diff.DbUniqueConstraintDiffOp;
import com.quemsi.model.flow.db.sql.diff.DiffEntityType;
import com.quemsi.model.flow.db.sql.diff.DiffOpType;
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServicePostgres implements DDLService{
    private Connection conn;

    @Override
    public boolean dropTables(String... tableNames) {
        try{
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
        try{
            Statement s = conn.createStatement();
            for(String sequenceName : sequenceNames){
                s.addBatch("DROP SEQUENCE IF EXISTS " + sequenceName + ";");
            }
            s.executeBatch();
            return true;
        }catch(Exception e){
            e.printStackTrace();
            throw Exceptions.server("failed-to-clear-sequences").withCause(e).get();
        }
    }
    @Override
    public void disableConstraints(Set<ReferenceInfo> constraints) {
        for(ReferenceInfo refInfo : constraints) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ");
            sb.append(refInfo.getSrcTableName()).append(" DROP FOREIGN KEY ")
            .append(refInfo.getConstraintName()).append(";");
            try{
                String dropConstraintSql = sb.toString();
                log.info("drop constraint sql :{}", dropConstraintSql);
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
            sb.append(refInfo.getSrcTableName()).append(" ADD CONSTRAINT ")
            .append(refInfo.getConstraintName())
            .append(" FOREIGN KEY (");
            Iterator<String> cIt = refInfo.getSrcColumnNames().iterator();
            while(cIt.hasNext()){
                String cName = cIt.next();
                sb.append("\"").append(cName).append("\"");
                if(cIt.hasNext()){
                    sb.append(", ");
                }
            }
            sb.append(") REFERENCES ").append(refInfo.getRefTableName()).append("(");
            cIt = refInfo.getRefColumnNames().iterator();
            while(cIt.hasNext()){
                String cName = cIt.next();
                sb.append("\"").append(cName).append("\"");
                if(cIt.hasNext()){
                    sb.append(", ");
                }
            }
            sb.append(");");
            try{
                String enableConstraintSql = sb.toString();
                log.info("enable constraint sql :{}", enableConstraintSql);
                Statement s = conn.createStatement();
                s.executeUpdate(enableConstraintSql);
            }catch(SQLException ignore){
                log.info("ignored enable constraint : " + refInfo.getConstraintName(), ignore);
            }
        }
    }
    private String columnType(String type, Integer maxLength){
        if("varchar".equals(type) && maxLength != null){
            return new StringBuffer(type).append("(").append(maxLength).append(")").toString();
        }
        return type;
    }
    @Override
    public void createTables(DbModel dbModel) {
        LinkedList<StringBuilder> scripts = new LinkedList<>();
        for(DbSequence seq : dbModel.getSequences()){
            StringBuilder seqStringBuilder = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ").append(seq.getName());
            if(seq.getIncrementBy() != null){
                seqStringBuilder.append(" INCREMENT BY ").append(seq.getIncrementBy());
            }
            if(seq.getMinValue() != null){
                seqStringBuilder.append(" MINVALUE ").append(seq.getMinValue());
            }else{
                seqStringBuilder.append(" NO MINVALUE ");
            }
            if(seq.getMaxValue() != null){
                seqStringBuilder.append(" MAXVALUE ").append(seq.getMaxValue());
            }else{
                seqStringBuilder.append(" NO MAXVALUE ");
            }
            if(seq.getLastValue() != null){
                seqStringBuilder.append(" START WITH ").append(seq.getLastValue());
            }else if(seq.getStartValue() != null){
                seqStringBuilder.append(" START WITH ").append(seq.getStartValue());
            }
            if(seq.getCacheSize() != null){
                seqStringBuilder.append(" CACHE ").append(seq.getCacheSize());
            }
            if(!seq.isCycle()){
                seqStringBuilder.append(" NO");
            }
            seqStringBuilder.append(" CYCLE;");
            scripts.add(seqStringBuilder);
        }
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream().collect(Collectors.groupingBy(r -> new StringBuilder(r.getSrcSchema()).append(".").append(r.getSrcTableName()).toString()));
		for(String tableName : dbModel.orderedTableNames()){
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			int index = 0;
            for(DbColumn c : columns){
				sb.append("  ").append("\"").append(c.getName()).append("\"").append(" ").append(columnType(c.getColumnType(), c.getMaxLength()));
				if(c.getColumnDefault() == null){
					if(c.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT").contains(c.getColumnType().toUpperCase())){
						sb.append(" DEFAULT NULL");
					}
				}else{
					sb.append(" DEFAULT " + c.getColumnDefault().replaceAll("::regclass", ""));
				}
				if(!c.isNullable()){
					sb.append(" NOT NULL");
				}
                if(index < columns.length - 1){
                    sb.append(",").append(System.lineSeparator());
                }
                index++;
			}
			if(table.getPkColumnNames().size() > 0){
                sb.append(",").append(System.lineSeparator());
                StringBuilder pkConst = new StringBuilder();
                Iterator<String> cIt = table.getPkColumnNames().iterator();
				String pkConstraintName = table.getPkConstraintName();
                while(cIt.hasNext()){
					String cName = cIt.next();
					if(pkConst.length() == 0){
                        pkConst.append("  ").append("CONSTRAINT ");
                        if(pkConstraintName != null){
                            pkConst.append(pkConstraintName).append(" PRIMARY KEY (");
                        }
                    }
                    pkConst.append("\"").append(cName).append("\"");
					if(cIt.hasNext()){
						pkConst.append(", ");
					}
				}
				pkConst.append(")");
                sb.append(pkConst.toString());
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
                        sb.append("\"").append(cName).append("\"");
                        if(cIt.hasNext()){
                            sb.append(", ");
                        }
                    }
					sb.append(") REFERENCES ").append(ref.getRefSchema()).append(".").append(ref.getRefTableName()).append(" (");
                    cIt = ref.getRefColumnNames().iterator();
                    while(cIt.hasNext()){
                        String cName = cIt.next();
                        sb.append("\"").append(cName).append("\"");
                        if(cIt.hasNext()){
                            sb.append(", ");
                        }
                    }
                    sb.append(")");
				}
			}
			sb.append(System.lineSeparator()).append(");");
			log.info("create script for {} : {}", tableName, sb.toString());
			scripts.add(sb);
            if(dbModel.getIndexes().containsKey(tableName)){
				Map<String, IndexInfo> indexes = dbModel.getIndexes().get(tableName);
				Iterator<String> indNameIt = indexes.keySet().iterator();
                while(indNameIt.hasNext()){
					String indName = indNameIt.next();
					IndexInfo indCols = indexes.get(indName);
                    StringBuilder indBuilder = new StringBuilder("CREATE ");
                    if(indCols.isUnique()){
                        indBuilder.append("UNIQUE ");
                    }
                    indBuilder.append("INDEX ").append("IF NOT EXISTS ").append(indName);
                    indBuilder.append(" ON ").append(tableName).append(" USING ").append(indCols.getIndexType()).append(" (");
                    Iterator<String> icIt = indCols.getColumns().iterator();
                    while(icIt.hasNext()){
                        String ic = icIt.next();
                        indBuilder.append("\"").append(ic).append("\"");
                        if(icIt.hasNext()){
                            indBuilder.append(", ");
                        }
                    }
                    indBuilder.append(");");
                    log.info("create index {} for table {} : {}", indName, tableName, indBuilder);
                    scripts.add(indBuilder);
				};
			}
			
		}
        for(ContraintInfo contraintInfo : dbModel.getContraintInfos()){
            StringBuilder sb = new StringBuilder("ALTER TABLE ONLY ").append(contraintInfo.qualifiedTableName()).append(" ADD CONSTRAINT ").append(contraintInfo.getConstraintName()).append(" UNIQUE").append(" (");
            Iterator<String> cIt = contraintInfo.getColumnNames().iterator();
            while(cIt.hasNext()){
                String cName = cIt.next();
                sb.append("\"").append(cName).append("\"");
                if(cIt.hasNext()){
                    sb.append(", ");
                }
            }
            sb.append(");");
            log.info("create unique constraint {} for table {} : {}", contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName(), sb.toString());
            scripts.add(sb);
        }
        for(CheckConstraint checkConstraint : dbModel.getCheckConstraints()){
            StringBuilder sb = new StringBuilder("ALTER TABLE ").append(checkConstraint.qualifiedTableName()).append(" ADD CONSTRAINT ").append(checkConstraint.getConstraintName()).append(" ").append(checkConstraint.getCondef()).append(";");
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
                log.info("ddl : {}", sb.toString());
				s.executeUpdate(sb.toString());
			}
		}catch(SQLException ignore){
			log.info("create tables sql", ignore);
		}
    }

    @Override
	public boolean checkSchema(String schema) throws SQLException{
		try(PreparedStatement ss = conn.prepareStatement(DatasourceFactoryPostgres.SQL_FOR_SCHEMA)){
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
     * Converts a DbModelDiff to a list of PostgreSQL SQL statements.
     * 
     * @param diff The database model diff containing operations to convert
     * @return List of SQL statements as strings
     */
    @Override
    public List<String> ddlFrom(DbModelDiff diff) {
        List<String> statements = new ArrayList<>();
        
        if (diff == null || diff.getOperations() == null || diff.getOperations().isEmpty()) {
            return statements;
        }
        
        for (DbModelDiffOp operation : diff.getOperations()) {
            List<String> opStatements = generateSqlForOperation(operation);
            statements.addAll(opStatements);
        }
        
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
                statements.addAll(generateSequenceSql((DbSequenceDiffOp) operation, opType));
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
            sb.append("  ").append("\"").append(c.getName()).append("\"").append(" ").append(columnType(c.getColumnType(), c.getMaxLength()));
            
            if (c.getColumnDefault() == null) {
                if (c.isNullable() && !Set.of("TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT").contains(c.getColumnType().toUpperCase())) {
                    sb.append(" DEFAULT NULL");
                }
            } else {
                sb.append(" DEFAULT ").append(c.getColumnDefault().replaceAll("::regclass", ""));
            }
            
            if (!c.isNullable()) {
                sb.append(" NOT NULL");
            }
            
            if (index < columns.length - 1) {
                sb.append(",").append(System.lineSeparator());
            }
            index++;
        }
        
        if (table.getPkColumnNames().size() > 0) {
            sb.append(",").append(System.lineSeparator());
            StringBuilder pkConst = new StringBuilder();
            Iterator<String> cIt = table.getPkColumnNames().iterator();
            String pkConstraintName = table.getPkConstraintName();
            
            while (cIt.hasNext()) {
                String cName = cIt.next();
                if (pkConst.length() == 0) {
                    pkConst.append("  ").append("CONSTRAINT ");
                    if (pkConstraintName != null) {
                        pkConst.append(pkConstraintName).append(" PRIMARY KEY (");
                    }
                }
                pkConst.append("\"").append(cName).append("\"");
                if (cIt.hasNext()) {
                    pkConst.append(", ");
                }
            }
            pkConst.append(")");
            sb.append(pkConst.toString());
        }
        
        sb.append(System.lineSeparator()).append(");");
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
                statements.add("ALTER TABLE " + tableName + " DROP COLUMN \"" + columnName + "\";");
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
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD COLUMN \"").append(column.getName()).append("\" ");
        sb.append(columnType(column.getColumnType(), column.getMaxLength()));
        
        if (column.getColumnDefault() != null) {
            sb.append(" DEFAULT ").append(column.getColumnDefault().replaceAll("::regclass", ""));
        }
        
        if (!column.isNullable()) {
            sb.append(" NOT NULL");
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
                .append(" ALTER COLUMN \"").append(newColumn.getName()).append("\" TYPE ")
                .append(columnType(newColumn.getColumnType(), newColumn.getMaxLength())).append(";");
            statements.add(sb.toString());
        }
        
        // Nullable change
        if (oldColumn.isNullable() != newColumn.isNullable()) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName)
                .append(" ALTER COLUMN \"").append(newColumn.getName()).append("\" ");
            if (newColumn.isNullable()) {
                sb.append("DROP NOT NULL");
            } else {
                sb.append("SET NOT NULL");
            }
            sb.append(";");
            statements.add(sb.toString());
        }
        
        // Default change
        if (!Objects.equals(oldColumn.getColumnDefault(), newColumn.getColumnDefault())) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName)
                .append(" ALTER COLUMN \"").append(newColumn.getName()).append("\" ");
            if (newColumn.getColumnDefault() == null) {
                sb.append("DROP DEFAULT");
            } else {
                sb.append("SET DEFAULT ").append(newColumn.getColumnDefault().replaceAll("::regclass", ""));
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
                    statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP CONSTRAINT " + ref.getConstraintName() + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldReference() != null) {
                    ReferenceInfo ref = operation.getOldReference();
                    statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP CONSTRAINT " + ref.getConstraintName() + ";");
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
            .append(" ADD CONSTRAINT ").append(ref.getConstraintName())
            .append(" FOREIGN KEY (");
        
        Iterator<String> cIt = ref.getSrcColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("\"").append(cName).append("\"");
            if (cIt.hasNext()) {
                sb.append(", ");
            }
        }
        
        sb.append(") REFERENCES ").append(ref.refQualifiedName()).append(" (");
        cIt = ref.getRefColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("\"").append(cName).append("\"");
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
                    statements.add("ALTER TABLE ONLY " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + constraint.getConstraintName() + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    ContraintInfo constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE ONLY " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + constraint.getConstraintName() + ";");
                }
                if (operation.getNewConstraint() != null) {
                    statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateAddUniqueConstraintSql(ContraintInfo constraint) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ONLY ").append(constraint.qualifiedTableName())
            .append(" ADD CONSTRAINT ").append(constraint.getConstraintName()).append(" UNIQUE (");
        
        Iterator<String> cIt = constraint.getColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("\"").append(cName).append("\"");
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
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " ADD CONSTRAINT " + constraint.getConstraintName() + " " + constraint.getCondef() + ";");
                }
                break;
            case DROP:
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + constraint.getConstraintName() + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + constraint.getConstraintName() + ";");
                }
                if (operation.getNewConstraint() != null) {
                    CheckConstraint constraint = operation.getNewConstraint();
                    statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " ADD CONSTRAINT " + constraint.getConstraintName() + " " + constraint.getCondef() + ";");
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
                    String qualifiedIndexName = CommonHelpers.qualifiedName(index.getSchemaName(), index.getIndexName());
                    statements.add("DROP INDEX " + qualifiedIndexName + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldIndex() != null) {
                    IndexInfo index = operation.getOldIndex();
                    String qualifiedIndexName = CommonHelpers.qualifiedName(index.getSchemaName(), index.getIndexName());
                    statements.add("DROP INDEX " + qualifiedIndexName + ";");
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
        sb.append("INDEX IF NOT EXISTS ");
        
        String qualifiedIndexName = CommonHelpers.qualifiedName(index.getSchemaName(), index.getIndexName());
        sb.append(qualifiedIndexName).append(" ON ").append(tableQualifiedName);
        
        if (index.getIndexType() != null && !index.getIndexType().isEmpty()) {
            sb.append(" USING ").append(index.getIndexType());
        }
        
        sb.append(" (");
        Iterator<String> icIt = index.getColumns().iterator();
        while (icIt.hasNext()) {
            String ic = icIt.next();
            sb.append("\"").append(ic).append("\"");
            if (icIt.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append(");");
        
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
        StringBuilder sb = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ").append(seq.qualifiedName());
        
        if (seq.getIncrementBy() != null) {
            sb.append(" INCREMENT BY ").append(seq.getIncrementBy());
        }
        
        if (seq.getMinValue() != null) {
            sb.append(" MINVALUE ").append(seq.getMinValue());
        } else {
            sb.append(" NO MINVALUE");
        }
        
        if (seq.getMaxValue() != null) {
            sb.append(" MAXVALUE ").append(seq.getMaxValue());
        } else {
            sb.append(" NO MAXVALUE");
        }
        
        if (seq.getLastValue() != null) {
            sb.append(" START WITH ").append(seq.getLastValue());
        } else if (seq.getStartValue() != null) {
            sb.append(" START WITH ").append(seq.getStartValue());
        }
        
        if (seq.getCacheSize() != null) {
            sb.append(" CACHE ").append(seq.getCacheSize());
        }
        
        if (!seq.isCycle()) {
            sb.append(" NO");
        }
        sb.append(" CYCLE;");
        
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
            if (newSeq.getMinValue() != null) {
                sb.append(" MINVALUE ").append(newSeq.getMinValue());
            } else {
                sb.append(" NO MINVALUE");
            }
            hasChanges = true;
        }
        
        if (!Objects.equals(oldSeq.getMaxValue(), newSeq.getMaxValue())) {
            if (newSeq.getMaxValue() != null) {
                sb.append(" MAXVALUE ").append(newSeq.getMaxValue());
            } else {
                sb.append(" NO MAXVALUE");
            }
            hasChanges = true;
        }
        
        if (!Objects.equals(oldSeq.getLastValue(), newSeq.getLastValue()) || 
            !Objects.equals(oldSeq.getStartValue(), newSeq.getStartValue())) {
            if (newSeq.getLastValue() != null) {
                sb.append(" RESTART WITH ").append(newSeq.getLastValue());
            } else if (newSeq.getStartValue() != null) {
                sb.append(" RESTART WITH ").append(newSeq.getStartValue());
            }
            hasChanges = true;
        }
        
        if (!Objects.equals(oldSeq.getCacheSize(), newSeq.getCacheSize()) && newSeq.getCacheSize() != null) {
            sb.append(" CACHE ").append(newSeq.getCacheSize());
            hasChanges = true;
        }
        
        if (oldSeq.isCycle() != newSeq.isCycle()) {
            if (!newSeq.isCycle()) {
                sb.append(" NO");
            }
            sb.append(" CYCLE");
            hasChanges = true;
        }
        
        if (hasChanges) {
            sb.append(";");
            statements.add(sb.toString());
        }
        
        return statements;
    }

}
