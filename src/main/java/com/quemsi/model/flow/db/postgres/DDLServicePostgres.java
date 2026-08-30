package com.quemsi.model.flow.db.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import com.quemsi.model.flow.db.sql.DbDomainType;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbFunction;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbSequence;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbTrigger;
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
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServicePostgres implements DDLService{
    private Connection conn;

	/** Builds a single multi-table DROP CASCADE; returns null when there are no names. */
	static String buildMultiTableDropSql(String... tableNames) {
		if (tableNames == null || tableNames.length == 0) {
			return null;
		}
		String quoted = Arrays.stream(tableNames)
			.map(CommonHelpers::doubleQuotedQualified)
			.collect(Collectors.joining(", "));
		return "DROP TABLE IF EXISTS " + quoted + " CASCADE";
	}

    @Override
    public boolean dropTables(String... tableNames) {
		String dropSql = buildMultiTableDropSql(tableNames);
		if (dropSql == null) {
			return true;
		}
        try{
			Statement s = conn.createStatement();
			log.info("drop tables sql :{}", dropSql);
			s.executeUpdate(dropSql);
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
                s.addBatch("DROP SEQUENCE IF EXISTS " + CommonHelpers.doubleQuotedQualified(sequenceName) + ";");
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
                s.addBatch("DROP VIEW IF EXISTS " + CommonHelpers.doubleQuotedQualified(viewName) + ";");
            }
            s.executeBatch();
            return true;
        } catch (Exception e) {
            throw Exceptions.server("failed-to-drop-views").withCause(e).get();
        }
    }

    @Override
    public void disableConstraints(Set<ReferenceInfo> constraints) {
		if (constraints == null || constraints.isEmpty()) {
			return;
		}
		try {
			Statement s = conn.createStatement();
			for (ReferenceInfo refInfo : constraints) {
				String dropConstraintSql = "ALTER TABLE " + CommonHelpers.doubleQuotedQualified(refInfo.srcQualifiedName())
					+ " DROP CONSTRAINT IF EXISTS " + CommonHelpers.doubleQuoted(refInfo.getConstraintName());
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
    public void enableContraints(Set<ReferenceInfo> constraints) {
		if (constraints == null || constraints.isEmpty()) {
			return;
		}
		try {
			Statement s = conn.createStatement();
			for (ReferenceInfo refInfo : constraints) {
				String enableConstraintSql = generateAddForeignKeySql(refInfo);
				if (enableConstraintSql.endsWith(";")) {
					enableConstraintSql = enableConstraintSql.substring(0, enableConstraintSql.length() - 1);
				}
				log.info("enable constraint sql :{}", enableConstraintSql);
				s.addBatch(enableConstraintSql);
			}
			try {
				s.executeBatch();
			} catch (SQLException ignore) {
				log.info("ignored enable constraints batch", ignore);
			}
		} catch (SQLException e) {
			log.info("ignored enable constraints", e);
		}
    }
    private String columnType(String type, Integer maxLength, Integer precision, Integer scale){
		if (type != null && type.startsWith("_") && type.length() > 1) {
			/* information_schema / pg udt_name for arrays is _text, _int4, ... */
			return type.substring(1) + "[]";
		}
        if(Set.of("varchar", "bpchar", "character varying", "character", "char").contains(type) && maxLength != null){
            return new StringBuffer(type).append("(").append(maxLength).append(")").toString();
        } else if(Set.of("numeric", "decimal", "real", "double precision").contains(type) && precision != null && scale != null){
            return new StringBuffer(type).append("(").append(precision).append(",").append(scale).append(")").toString();
        }
        return type;
    }

	static String createEnumTypeSql(DbEnumType enumType) {
		StringBuilder sb = new StringBuilder("CREATE TYPE ")
			.append(CommonHelpers.doubleQuotedQualified(enumType.getSchema(), enumType.getName()))
			.append(" AS ENUM (");
		List<String> labels = enumType.getLabels();
		for (int i = 0; i < labels.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append("'").append(labels.get(i).replace("'", "''")).append("'");
		}
		sb.append(")");
		return sb.toString();
	}

	static String createDomainTypeSql(DbDomainType domain) {
		StringBuilder sb = new StringBuilder("CREATE DOMAIN ")
			.append(CommonHelpers.doubleQuotedQualified(domain.getSchema(), domain.getName()))
			.append(" AS ").append(domain.getBaseType());
		if (domain.getDefaultExpression() != null && !domain.getDefaultExpression().isBlank()) {
			sb.append(" DEFAULT ").append(domain.getDefaultExpression());
		}
		if (domain.isNotNull()) {
			sb.append(" NOT NULL");
		}
		if (domain.getCheckConstraintDef() != null && !domain.getCheckConstraintDef().isBlank()) {
			if (domain.getCheckConstraintName() != null && !domain.getCheckConstraintName().isBlank()) {
				sb.append(" CONSTRAINT ").append(CommonHelpers.doubleQuoted(domain.getCheckConstraintName()));
			}
			sb.append(" ").append(domain.getCheckConstraintDef());
		}
		return sb.toString();
	}

	static String dropTriggerSql(DbTrigger trigger) {
		return "DROP TRIGGER IF EXISTS " + CommonHelpers.doubleQuoted(trigger.getName())
			+ " ON " + CommonHelpers.doubleQuotedQualified(trigger.getSchema(), trigger.getTableName());
	}

	private boolean typeExists(String schema, String typeName) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
			"select 1 from pg_catalog.pg_type t "
				+ "inner join pg_catalog.pg_namespace n on n.oid = t.typnamespace "
				+ "where n.nspname = ? and t.typname = ?")) {
			ps.setString(1, schema);
			ps.setString(2, typeName);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	static Set<String> uniqueConstraintNamesForTable(DbModel dbModel, String qualifiedTableName) {
		Set<String> names = new HashSet<>();
		if (dbModel.getContraintInfos() == null) {
			return names;
		}
		for (ContraintInfo info : dbModel.getContraintInfos()) {
			if (qualifiedTableName.equals(info.qualifiedTableName()) && info.getConstraintName() != null) {
				names.add(info.getConstraintName());
			}
		}
		return names;
	}

	Set<String> existingQualifiedTables(Set<String> schemas) throws SQLException {
		Set<String> result = new HashSet<>();
		if (schemas == null || schemas.isEmpty()) {
			return result;
		}
		List<String> schemaList = new ArrayList<>(schemas);
		String placeholders = schemaList.stream().map(s -> "?").collect(Collectors.joining(", "));
		String sql = "select n.nspname, c.relname from pg_catalog.pg_class c "
			+ "inner join pg_catalog.pg_namespace n on n.oid = c.relnamespace "
			+ "where c.relkind = 'r' and n.nspname in (" + placeholders + ")";
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			for (int i = 0; i < schemaList.size(); i++) {
				ps.setString(i + 1, schemaList.get(i));
			}
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					result.add(CommonHelpers.qualifiedName(rs.getString(1), rs.getString(2)));
				}
			}
		}
		return result;
	}

    @Override
    public void createTables(DbModel dbModel) {
        LinkedList<StringBuilder> scripts = new LinkedList<>();
		Set<String> existingTables;
		try {
			existingTables = existingQualifiedTables(dbModel.getSchemas());
		} catch (SQLException e) {
			throw Exceptions.server("failed-to-create-tables").withCause(e).get();
		}
        for(DbSequence seq : dbModel.getSequences()){
            StringBuilder seqStringBuilder = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ")
				.append(CommonHelpers.doubleQuotedQualified(seq.getSchema(), seq.getName()));
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
		/* FKs are applied after data load via enableContraints — omit from CREATE for faster DDL */
		for(String tableName : dbModel.orderedTableNames()){
			if (existingTables.contains(tableName)) {
				log.info("table {} already exists, skipping create", tableName);
				continue;
			}
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			String quotedTable = CommonHelpers.doubleQuotedQualified(table.getSchema(), table.getName());
			StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(quotedTable).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			int index = 0;
            for(DbColumn c : columns){
				sb.append("  ").append(CommonHelpers.doubleQuoted(c.getName())).append(" ").append(columnType(c.getColumnType(), c.getMaxLength(), c.getNumPrecision(), c.getNumScale()));
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
                            pkConst.append(CommonHelpers.doubleQuoted(pkConstraintName)).append(" PRIMARY KEY (");
                        }
                    }
                    pkConst.append(CommonHelpers.doubleQuoted(cName));
					if(cIt.hasNext()){
						pkConst.append(", ");
					}
				}
				pkConst.append(")");
                sb.append(pkConst.toString());
			}
			sb.append(System.lineSeparator()).append(");");
			log.info("create script for {} : {}", tableName, sb.toString());
			scripts.add(sb);
            Map<String, IndexInfo> indexes = dbModel.indexesForTable(tableName);
			Set<String> uniqueConstraintNames = uniqueConstraintNamesForTable(dbModel, tableName);
            for (Map.Entry<String, IndexInfo> entry : indexes.entrySet()) {
                String indName = entry.getKey();
				if (indName != null && uniqueConstraintNames.contains(indName)) {
					log.info("skipping unique-constraint index {} on {}", indName, tableName);
					continue;
				}
                IndexInfo indCols = entry.getValue();
                StringBuilder indBuilder = new StringBuilder("CREATE ");
                if(indCols.isUnique()){
                    indBuilder.append("UNIQUE ");
                }
                indBuilder.append("INDEX ").append("IF NOT EXISTS ").append(CommonHelpers.doubleQuoted(indName));
                indBuilder.append(" ON ").append(quotedTable).append(" USING ").append(indCols.getIndexType()).append(" (");
                Iterator<String> icIt = indCols.getColumns().iterator();
                while(icIt.hasNext()){
                    String ic = icIt.next();
                    indBuilder.append(CommonHelpers.doubleQuoted(ic));
                    if(icIt.hasNext()){
                        indBuilder.append(", ");
                    }
                }
                indBuilder.append(");");
                log.info("create index {} for table {} : {}", indName, tableName, indBuilder);
                scripts.add(indBuilder);
            }
			
		}
        for(ContraintInfo contraintInfo : dbModel.getContraintInfos()){
			if (existingTables.contains(contraintInfo.qualifiedTableName())) {
				log.info("unique constraint {} on {} skipped, table already exists",
					contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName());
				continue;
			}
            StringBuilder sb = new StringBuilder("ALTER TABLE ONLY ")
				.append(CommonHelpers.doubleQuotedQualified(contraintInfo.qualifiedTableName()))
				.append(" ADD CONSTRAINT ").append(CommonHelpers.doubleQuoted(contraintInfo.getConstraintName())).append(" UNIQUE").append(" (");
            Iterator<String> cIt = contraintInfo.getColumnNames().iterator();
            while(cIt.hasNext()){
                String cName = cIt.next();
                sb.append(CommonHelpers.doubleQuoted(cName));
                if(cIt.hasNext()){
                    sb.append(", ");
                }
            }
            sb.append(");");
            log.info("create unique constraint {} for table {} : {}", contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName(), sb.toString());
            scripts.add(sb);
        }
        for(CheckConstraint checkConstraint : dbModel.getCheckConstraints()){
			if (existingTables.contains(checkConstraint.qualifiedTableName())) {
				log.info("check constraint {} on {} skipped, table already exists",
					checkConstraint.getConstraintName(), checkConstraint.qualifiedTableName());
				continue;
			}
            StringBuilder sb = new StringBuilder("ALTER TABLE ")
				.append(CommonHelpers.doubleQuotedQualified(checkConstraint.qualifiedTableName()))
				.append(" ADD CONSTRAINT ").append(CommonHelpers.doubleQuoted(checkConstraint.getConstraintName())).append(" ").append(checkConstraint.getCondef()).append(";");
            log.info("create check constraint {} for table {} : {}", checkConstraint.getConstraintName(), checkConstraint.qualifiedTableName(), sb.toString());
            scripts.add(sb);
        }
		try{
            for(String schema : dbModel.getSchemas()){
                if(!checkSchema(schema)){
                    StringBuilder csSql = new StringBuilder("create schema ").append("\"").append(schema).append("\"").append(";");
                    Statement css = conn.createStatement();
                    css.execute(csSql.toString());
                }
            }
			if (dbModel.getEnumTypes() != null) {
				for (DbEnumType enumType : dbModel.getEnumTypes()) {
					if (typeExists(enumType.getSchema(), enumType.getName())) {
						continue;
					}
					String sql = createEnumTypeSql(enumType);
					log.info("ddl : {}", sql);
					try (Statement s = conn.createStatement()) {
						s.executeUpdate(sql);
					}
				}
			}
			if (dbModel.getDomainTypes() != null) {
				for (DbDomainType domain : dbModel.getDomainTypes()) {
					if (typeExists(domain.getSchema(), domain.getName())) {
						continue;
					}
					String sql = createDomainTypeSql(domain);
					log.info("ddl : {}", sql);
					try (Statement s = conn.createStatement()) {
						s.executeUpdate(sql);
					}
				}
			}
			if (scripts.isEmpty()) {
				return;
			}
			Statement s = conn.createStatement();
			for(StringBuilder sb : scripts){
				String sql = sb.toString().trim();
				while (sql.endsWith(";")) {
					sql = sql.substring(0, sql.length() - 1).trim();
				}
                log.info("ddl : {}", sql);
				s.addBatch(sql);
			}
			log.info("create tables batch size {}", scripts.size());
			s.executeBatch();
		}catch(SQLException e){
			log.error("create tables sql failed", e);
			throw Exceptions.server("failed-to-create-tables").withCause(e).get();
		}
    }

    @Override
    public void createFunctions(DbModel dbModel) {
        if (dbModel.getFunctions() == null || dbModel.getFunctions().isEmpty()) {
            return;
        }
        try {
            Statement s = conn.createStatement();
            // Match pg_dump: SQL-language bodies are checked at CREATE time, so forward
            // references (e.g. film_in_stock -> inventory_in_stock) fail without this.
            s.execute("SET check_function_bodies = false");
            try {
                for (DbFunction function : dbModel.getFunctions()) {
                    String sql = createFunctionSql(function);
                    log.info("ddl : {}", sql);
                    s.executeUpdate(sql);
                }
            } finally {
                s.execute("RESET check_function_bodies");
            }
        } catch (SQLException e) {
            throw Exceptions.server("failed-to-create-functions").withCause(e).get();
        }
    }

    static String createFunctionSql(DbFunction function) {
        String def = function.getDefinition();
        if (def == null || def.isBlank()) {
            throw Exceptions.server("missing-function-definition")
                .withExtra("function", function.qualifiedName()).get();
        }
        String trimmed = def.trim();
        if (DbFunction.TYPE_AGGREGATE.equalsIgnoreCase(function.resolvedRoutineType())
            || trimmed.regionMatches(true, 0, "CREATE AGGREGATE", 0, "CREATE AGGREGATE".length())
            || trimmed.regionMatches(true, 0, "CREATE OR REPLACE AGGREGATE", 0, "CREATE OR REPLACE AGGREGATE".length())) {
            if (trimmed.regionMatches(true, 0, "CREATE AGGREGATE", 0, "CREATE AGGREGATE".length())
                && !trimmed.regionMatches(true, 0, "CREATE OR REPLACE AGGREGATE", 0, "CREATE OR REPLACE AGGREGATE".length())) {
                return "CREATE OR REPLACE AGGREGATE" + trimmed.substring("CREATE AGGREGATE".length());
            }
            return trimmed;
        }
        // pg_get_functiondef may return CREATE FUNCTION; prefer OR REPLACE for restore
        if (trimmed.regionMatches(true, 0, "CREATE FUNCTION", 0, "CREATE FUNCTION".length())
            && !trimmed.regionMatches(true, 0, "CREATE OR REPLACE FUNCTION", 0, "CREATE OR REPLACE FUNCTION".length())) {
            return "CREATE OR REPLACE FUNCTION" + trimmed.substring("CREATE FUNCTION".length());
        }
        if (trimmed.regionMatches(true, 0, "CREATE PROCEDURE", 0, "CREATE PROCEDURE".length())
            && !trimmed.regionMatches(true, 0, "CREATE OR REPLACE PROCEDURE", 0, "CREATE OR REPLACE PROCEDURE".length())) {
            return "CREATE OR REPLACE PROCEDURE" + trimmed.substring("CREATE PROCEDURE".length());
        }
        return trimmed;
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

	@Override
	public void createTriggers(DbModel dbModel) {
		if (dbModel.getTriggers() == null || dbModel.getTriggers().isEmpty()) {
			return;
		}
		try {
			Statement s = conn.createStatement();
			for (DbTrigger trigger : dbModel.getTriggers()) {
				String dropSql = dropTriggerSql(trigger);
				log.info("ddl : {}", dropSql);
				s.executeUpdate(dropSql);
				String createSql = trigger.getDefinition();
				if (createSql == null || createSql.isBlank()) {
					throw Exceptions.server("missing-trigger-definition")
						.withExtra("trigger", trigger.getName())
						.withExtra("table", trigger.qualifiedTableName()).get();
				}
				log.info("ddl : {}", createSql);
				s.executeUpdate(createSql);
			}
		} catch (SQLException e) {
			throw Exceptions.server("failed-to-create-triggers").withCause(e).get();
		}
	}

	@Override
	public boolean dropDomains(String... domainNames) {
		if (domainNames == null || domainNames.length == 0) {
			return true;
		}
		try {
			Statement s = conn.createStatement();
			for (String domainName : domainNames) {
				s.addBatch("DROP DOMAIN IF EXISTS " + CommonHelpers.doubleQuotedQualified(domainName) + " CASCADE");
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-drop-domains").withCause(e).get();
		}
	}

	@Override
	public boolean dropEnumTypes(String... typeNames) {
		if (typeNames == null || typeNames.length == 0) {
			return true;
		}
		try {
			Statement s = conn.createStatement();
			for (String typeName : typeNames) {
				s.addBatch("DROP TYPE IF EXISTS " + CommonHelpers.doubleQuotedQualified(typeName) + " CASCADE");
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-drop-enum-types").withCause(e).get();
		}
	}

    static String dropViewSql(String qualifiedName) {
        return "DROP VIEW IF EXISTS " + CommonHelpers.doubleQuotedQualified(qualifiedName) + ";";
    }

    static String createViewSql(DbView view) {
        return "CREATE VIEW " + CommonHelpers.doubleQuotedQualified(view.getSchema(), view.getName()) + " AS " + view.getDefinition()
            + (view.getDefinition() != null && view.getDefinition().trim().endsWith(";") ? "" : ";");
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
                    statements.add("DROP TABLE IF EXISTS " + CommonHelpers.doubleQuotedQualified(operation.getQualifiedName()) + ";");
                }
                break;
            case MODIFY:
                // For MODIFY, we drop and recreate the table
                if (operation.getOldTable() != null) {
                    statements.add("DROP TABLE IF EXISTS " + CommonHelpers.doubleQuotedQualified(operation.getQualifiedName()) + ";");
                }
                if (operation.getNewTable() != null) {
                    statements.add(generateCreateTableSql(operation.getNewTable()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateCreateTableSql(DbTable table) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
			.append(CommonHelpers.doubleQuotedQualified(table.getSchema(), table.getName())).append(" (").append(System.lineSeparator());
        DbColumn[] columns = table.orderedColumns();
        int index = 0;
        
        for (DbColumn c : columns) {
            sb.append("  ").append(CommonHelpers.doubleQuoted(c.getName())).append(" ").append(columnType(c.getColumnType(), c.getMaxLength(), c.getNumPrecision(), c.getNumScale()));
            
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
                        pkConst.append(CommonHelpers.doubleQuoted(pkConstraintName)).append(" PRIMARY KEY (");
                    }
                }
                pkConst.append(CommonHelpers.doubleQuoted(cName));
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
        String tableName = CommonHelpers.doubleQuotedQualified(operation.getTableQualifiedName());
        String columnName = operation.getColumnName();
        
        switch (opType) {
            case CREATE:
                if (operation.getNewColumn() != null) {
                    statements.add(generateAddColumnSql(tableName, operation.getNewColumn()));
                }
                break;
            case DROP:
                statements.add("ALTER TABLE " + tableName + " DROP COLUMN " + CommonHelpers.doubleQuoted(columnName) + ";");
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
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD COLUMN ").append(CommonHelpers.doubleQuoted(column.getName())).append(" ");
        sb.append(columnType(column.getColumnType(), column.getMaxLength(), column.getNumPrecision(), column.getNumScale()));
        
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
            !Objects.equals(oldColumn.getMaxLength(), newColumn.getMaxLength()) ||
            !Objects.equals(oldColumn.getNumPrecision(), newColumn.getNumPrecision()) ||
            !Objects.equals(oldColumn.getNumScale(), newColumn.getNumScale())
        ) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName)
                .append(" ALTER COLUMN ").append(CommonHelpers.doubleQuoted(newColumn.getName())).append(" TYPE ")
                .append(columnType(newColumn.getColumnType(), newColumn.getMaxLength(), newColumn.getNumPrecision(), newColumn.getNumScale())).append(";");
            statements.add(sb.toString());
        }
        
        // Nullable change
        if (oldColumn.isNullable() != newColumn.isNullable()) {
            StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName)
                .append(" ALTER COLUMN ").append(CommonHelpers.doubleQuoted(newColumn.getName())).append(" ");
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
                .append(" ALTER COLUMN ").append(CommonHelpers.doubleQuoted(newColumn.getName())).append(" ");
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
                    statements.add("ALTER TABLE " + CommonHelpers.doubleQuotedQualified(ref.srcQualifiedName())
						+ " DROP CONSTRAINT " + CommonHelpers.doubleQuoted(ref.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldReference() != null) {
                    ReferenceInfo ref = operation.getOldReference();
                    statements.add("ALTER TABLE " + CommonHelpers.doubleQuotedQualified(ref.srcQualifiedName())
						+ " DROP CONSTRAINT " + CommonHelpers.doubleQuoted(ref.getConstraintName()) + ";");
                }
                if (operation.getNewReference() != null) {
                    statements.add(generateAddForeignKeySql(operation.getNewReference()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateAddForeignKeySql(ReferenceInfo ref) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(CommonHelpers.doubleQuotedQualified(ref.srcQualifiedName()))
            .append(" ADD CONSTRAINT ").append(CommonHelpers.doubleQuoted(ref.getConstraintName())).append(" ")
            .append(" FOREIGN KEY (");
        
        Iterator<String> cIt = ref.getSrcColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append(CommonHelpers.doubleQuoted(cName));
            if (cIt.hasNext()) {
                sb.append(", ");
            }
        }
        
        sb.append(") REFERENCES ").append(CommonHelpers.doubleQuotedQualified(ref.refQualifiedName())).append(" (");
        cIt = ref.getRefColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append(CommonHelpers.doubleQuoted(cName));
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
                    statements.add("ALTER TABLE ONLY " + CommonHelpers.doubleQuotedQualified(constraint.qualifiedTableName())
						+ " DROP CONSTRAINT " + CommonHelpers.doubleQuoted(constraint.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    ContraintInfo constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE ONLY " + CommonHelpers.doubleQuotedQualified(constraint.qualifiedTableName())
						+ " DROP CONSTRAINT " + CommonHelpers.doubleQuoted(constraint.getConstraintName()) + ";");
                }
                if (operation.getNewConstraint() != null) {
                    statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateAddUniqueConstraintSql(ContraintInfo constraint) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ONLY ").append(CommonHelpers.doubleQuotedQualified(constraint.qualifiedTableName()))
            .append(" ADD CONSTRAINT ").append(CommonHelpers.doubleQuoted(constraint.getConstraintName())).append(" UNIQUE (");
        
        Iterator<String> cIt = constraint.getColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append(CommonHelpers.doubleQuoted(cName));
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
                    statements.add("ALTER TABLE " + CommonHelpers.doubleQuotedQualified(constraint.qualifiedTableName())
						+ " ADD CONSTRAINT " + CommonHelpers.doubleQuoted(constraint.getConstraintName()) + " " + constraint.getCondef() + ";");
                }
                break;
            case DROP:
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + CommonHelpers.doubleQuotedQualified(constraint.qualifiedTableName())
						+ " DROP CONSTRAINT " + CommonHelpers.doubleQuoted(constraint.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + CommonHelpers.doubleQuotedQualified(constraint.qualifiedTableName())
						+ " DROP CONSTRAINT " + CommonHelpers.doubleQuoted(constraint.getConstraintName()) + ";");
                }
                if (operation.getNewConstraint() != null) {
                    CheckConstraint constraint = operation.getNewConstraint();
                    statements.add("ALTER TABLE " + CommonHelpers.doubleQuotedQualified(constraint.qualifiedTableName())
						+ " ADD CONSTRAINT " + CommonHelpers.doubleQuoted(constraint.getConstraintName()) + " " + constraint.getCondef() + ";");
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
                    String qualifiedIndexName = CommonHelpers.doubleQuotedQualified(index.getSchemaName(), index.getIndexName());
                    statements.add("DROP INDEX " + qualifiedIndexName + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldIndex() != null) {
                    IndexInfo index = operation.getOldIndex();
                    String qualifiedIndexName = CommonHelpers.doubleQuotedQualified(index.getSchemaName(), index.getIndexName());
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
        
        sb.append(CommonHelpers.doubleQuoted(index.getIndexName()))
			.append(" ON ").append(CommonHelpers.doubleQuotedQualified(tableQualifiedName));
        
        if (index.getIndexType() != null && !index.getIndexType().isEmpty()) {
            sb.append(" USING ").append(index.getIndexType());
        }
        
        sb.append(" (");
        Iterator<String> icIt = index.getColumns().iterator();
        while (icIt.hasNext()) {
            String ic = icIt.next();
            sb.append(CommonHelpers.doubleQuoted(ic));
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
                    statements.add("DROP SEQUENCE IF EXISTS " + CommonHelpers.doubleQuotedQualified(operation.getQualifiedName()) + ";");
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
        StringBuilder sb = new StringBuilder("CREATE SEQUENCE IF NOT EXISTS ")
			.append(CommonHelpers.doubleQuotedQualified(seq.getSchema(), seq.getName()));
        
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
        StringBuilder sb = new StringBuilder("ALTER SEQUENCE ")
			.append(CommonHelpers.doubleQuotedQualified(newSeq.getSchema(), newSeq.getName()));
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
        
        // Do not alter startValue/lastValue — not treated as schema structure (UpdateSequences handles counters).
        
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
