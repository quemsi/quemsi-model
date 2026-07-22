package com.quemsi.model.flow.db.sqlserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbDomainType;
import com.quemsi.model.flow.db.sql.DbFullTextCatalog;
import com.quemsi.model.flow.db.sql.DbFullTextIndex;
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
import com.quemsi.model.flow.db.sql.DbXmlSchemaCollection;
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
public class DDLServiceSqlserver implements DDLService{
    private Connection conn;

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

	/** Single multi-table DROP; SQL Server drops FKs among listed tables without prior ALTER. */
	static String buildMultiTableDropSql(String... tableNames) {
		if (tableNames == null || tableNames.length == 0) {
			return null;
		}
		StringBuilder sb = new StringBuilder("DROP TABLE IF EXISTS ");
		for (int i = 0; i < tableNames.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(CommonHelpers.bracketQuotedQualified(tableNames[i]));
		}
		return sb.toString();
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
				s.addBatch(dropViewSql(viewName));
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
		if (constraints == null || constraints.isEmpty()) {
			return;
		}
		try {
			Statement s = conn.createStatement();
			for (ReferenceInfo refInfo : constraints) {
				StringBuilder sb = new StringBuilder("ALTER TABLE ");
				sb.append(CommonHelpers.bracketQuotedQualified(refInfo.srcQualifiedName())).append(" DROP CONSTRAINT ");
				appendBracketQuoted(sb, refInfo.getConstraintName());
				String dropConstraintSql = sb.toString();
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

    static String columnType(String type, Integer maxLength, Integer precision, Integer scale){
		/* sys.columns.max_length is bytes: nchar/nvarchar use 2 bytes per char; char/varchar/binary are 1:1. */
		if (Set.of("char", "varchar", "binary", "varbinary").contains(type) && maxLength != null) {
			StringBuilder sb = new StringBuilder(type).append("(");
			if (maxLength == -1) {
				sb.append("MAX");
			} else {
				sb.append(maxLength);
			}
			return sb.append(")").toString();
		}
		if (Set.of("nchar", "nvarchar").contains(type) && maxLength != null) {
			StringBuilder sb = new StringBuilder(type).append("(");
			if (maxLength == -1) {
				sb.append("MAX");
			} else {
				sb.append(maxLength / 2);
			}
			return sb.append(")").toString();
		}
		if (Set.of("decimal", "numeric").contains(type) && precision != null) {
			return new StringBuilder(type).append("(").append(precision).append(",")
					.append(scale != null ? scale : 0).append(")").toString();
		}
		return type;
    }

	static String columnTypeSql(DbColumn column) {
		if (StringUtils.hasText(column.getXmlSchemaCollection())) {
			return "xml(" + CommonHelpers.bracketQuotedQualified(column.getXmlSchemaCollection()) + ")";
		}
		return columnType(column.getDataType(), column.getMaxLength(), column.getNumPrecision(), column.getNumScale());
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
		/* FKs are applied after data load via enableContraints — omit from CREATE for faster DDL */
		for(String tableName : dbModel.orderedTableNames()){
			if(existingTables.contains(tableName)){
				log.info("table {} already exists in schema {} skipping", tableName, dbModel.getSchemas());
				continue;
			}
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			boolean hasClustedIndex = dbModel.indexesForTable(tableName)
				.values().stream().map(ii -> "CLUSTERED".equals(ii.getIndexType())).reduce(Boolean.FALSE, (a, v) -> a || v);
			String quotedTableName = CommonHelpers.bracketQuotedQualified(tableName);
			StringBuilder sb = new StringBuilder("CREATE TABLE ").append(quotedTableName).append(" (").append(System.lineSeparator());
			DbColumn[] columns = table.orderedColumns();
			int index = 0;
			for(DbColumn c : columns){
				sb.append("  ");
				appendBracketQuoted(sb, c.getName());
				sb.append(" ").append(columnTypeSql(c));
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
				if(index < columns.length - 1){
					sb.append(",").append(System.lineSeparator());
				}
				index++;
			}
			if(table.getPkColumnNames().size() > 0){
				sb.append(",").append(System.lineSeparator());
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
					appendBracketQuoted(sb, cName);
					if(cIt.hasNext()){
						sb.append(", ");
					}
				}
				sb.append(")");
			}
			sb.append(System.lineSeparator()).append(");");
			log.info("create script for {} : {}", tableName, sb.toString());
			scripts.add(sb);
			Map<String, IndexInfo> indexes = dbModel.indexesForTable(tableName);
			List<IndexInfo> orderedIndexes = new ArrayList<>(indexes.values());
			orderedIndexes.sort(Comparator
					.comparingInt(DDLServiceSqlserver::xmlIndexCreateOrder)
					.thenComparing(IndexInfo::getIndexName, Comparator.nullsLast(String::compareToIgnoreCase)));
			for (IndexInfo indCols : orderedIndexes) {
					if (indCols.getColumns() != null && indCols.getColumns().contains("rowguid")) {
						continue;
					}
					StringBuilder indBuilder = new StringBuilder(createIndexSql(indCols, quotedTableName));
					log.info("index sql : {}", indBuilder);
					scripts.add(indBuilder);
			}
		}
		for(ContraintInfo contraintInfo : dbModel.getContraintInfos()){
			if(existingTables.contains(contraintInfo.qualifiedTableName())){
				log.info("unique constraint {} already exists on {} skipping", contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName());
				continue;
			}
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(CommonHelpers.bracketQuotedQualified(contraintInfo.qualifiedTableName())).append(" ADD CONSTRAINT ");
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
			if (StringUtils.isEmptyOrNull(checkConstraint.getCondef())) {
				throw Exceptions.server("view-definition-permission-required")
						.withExtra("requiredPermission", "VIEW DEFINITION")
						.withExtra("objectType", "check-constraint")
						.withExtra("objectName", checkConstraint.getConstraintName())
						.withExtra("tableName", checkConstraint.qualifiedTableName())
						.get();
			}
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(CommonHelpers.bracketQuotedQualified(checkConstraint.qualifiedTableName())).append(" WITH CHECK ADD CONSTRAINT ");
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
			createDomainTypes(dbModel);
			createXmlSchemaCollections(dbModel);
			if (scripts.isEmpty()) {
				return;
			}
			Statement s = conn.createStatement();
			for(StringBuilder sb : scripts){
				String sql = sb.toString().trim();
				while (sql.endsWith(";")) {
					sql = sql.substring(0, sql.length() - 1).trim();
				}
				log.info("sql : {}", sql);
				s.addBatch(sql);
			}
			log.info("create tables batch size {}", scripts.size());
			s.executeBatch();
		}catch(SQLException e){
			throw Exceptions.server("failed-to-create-tables").withCause(e).get();
		}
    }

	private void createDomainTypes(DbModel dbModel) throws SQLException {
		if (dbModel.getDomainTypes() == null || dbModel.getDomainTypes().isEmpty()) {
			return;
		}
		try (Statement s = conn.createStatement()) {
			for (DbDomainType domain : dbModel.getDomainTypes()) {
				String qualified = CommonHelpers.bracketQuotedQualified(domain.getSchema(), domain.getName());
				if (typeExists(domain.getSchema(), domain.getName())) {
					log.info("alias type {} already exists skipping", qualified);
					continue;
				}
				String sql = createAliasTypeSql(domain);
				log.info("ddl : {}", sql);
				s.executeUpdate(sql);
			}
		}
	}

	private void createXmlSchemaCollections(DbModel dbModel) throws SQLException {
		if (dbModel.getXmlSchemaCollections() == null || dbModel.getXmlSchemaCollections().isEmpty()) {
			return;
		}
		try (Statement s = conn.createStatement()) {
			for (DbXmlSchemaCollection collection : dbModel.getXmlSchemaCollections()) {
				if (xmlSchemaCollectionExists(collection.getSchema(), collection.getName())) {
					log.info("xml schema collection {} already exists skipping", collection.qualifiedName());
					continue;
				}
				String sql = createXmlSchemaCollectionSql(collection);
				log.info("ddl : CREATE XML SCHEMA COLLECTION {}",
					CommonHelpers.bracketQuotedQualified(collection.getSchema(), collection.getName()));
				s.executeUpdate(sql);
			}
		} catch (SQLException e) {
			throw Exceptions.server("failed-to-create-xml-schema-collections").withCause(e).get();
		}
	}

	static String createXmlSchemaCollectionSql(DbXmlSchemaCollection collection) {
		if (collection.getDefinition() == null || collection.getDefinition().isBlank()) {
			throw Exceptions.server("missing-object-definition")
				.withExtra("objectType", "xml-schema-collection")
				.withExtra("objectName", collection.qualifiedName())
				.get();
		}
		return "CREATE XML SCHEMA COLLECTION "
			+ CommonHelpers.bracketQuotedQualified(collection.getSchema(), collection.getName())
			+ " AS N'" + collection.getDefinition().replace("'", "''") + "'";
	}

	static String dropXmlSchemaCollectionSql(DbXmlSchemaCollection collection) {
		StringBuilder sb = new StringBuilder();
		sb.append("IF EXISTS (SELECT 1 FROM sys.xml_schema_collections WHERE name = N'");
		sb.append(collection.getName().replace("'", "''"));
		sb.append("' AND schema_name(schema_id) = N'");
		sb.append(collection.getSchema().replace("'", "''"));
		sb.append("') DROP XML SCHEMA COLLECTION ");
		sb.append(CommonHelpers.bracketQuotedQualified(collection.getSchema(), collection.getName()));
		return sb.toString();
	}

	private boolean xmlSchemaCollectionExists(String schema, String name) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
				"select 1 from sys.xml_schema_collections where schema_name(schema_id) = ? and name = ?")) {
			ps.setString(1, schema);
			ps.setString(2, name);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	@Override
	public boolean dropXmlSchemaCollections(DbModel dbModel) {
		if (dbModel == null || dbModel.getXmlSchemaCollections() == null || dbModel.getXmlSchemaCollections().isEmpty()) {
			return true;
		}
		try {
			Statement s = conn.createStatement();
			for (DbXmlSchemaCollection collection : dbModel.getXmlSchemaCollections()) {
				String sql = dropXmlSchemaCollectionSql(collection);
				log.info("ddl : {}", sql);
				s.addBatch(sql);
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-drop-xml-schema-collections").withCause(e).get();
		}
	}

	static String createAliasTypeSql(DbDomainType domain) {
		if (domain.getBaseType() == null || domain.getBaseType().isBlank()) {
			throw Exceptions.server("missing-object-definition")
				.withExtra("objectType", "alias-type")
				.withExtra("objectName", domain.qualifiedName())
				.get();
		}
		StringBuilder sb = new StringBuilder("CREATE TYPE ")
			.append(CommonHelpers.bracketQuotedQualified(domain.getSchema(), domain.getName()))
			.append(" FROM ").append(domain.getBaseType());
		if (domain.isNotNull()) {
			sb.append(" NOT NULL");
		}
		return sb.toString();
	}

	private boolean typeExists(String schema, String typeName) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
				"select 1 from sys.types t where schema_name(t.schema_id) = ? and t.name = ? and t.is_user_defined = 1")) {
			ps.setString(1, schema);
			ps.setString(2, typeName);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
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

	@Override
	public boolean dropFunctions(DbModel dbModel) {
		if (dbModel == null || dbModel.getFunctions() == null || dbModel.getFunctions().isEmpty()) {
			return true;
		}
		try {
			Statement s = conn.createStatement();
			for (DbFunction function : dbModel.getFunctions()) {
				String dropSql = dropRoutineSql(function);
				log.info("ddl : {}", dropSql);
				s.addBatch(dropSql);
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-drop-functions").withCause(e).get();
		}
	}

	@Override
	public boolean dropTriggers(DbModel dbModel) {
		if (dbModel == null || dbModel.getTriggers() == null || dbModel.getTriggers().isEmpty()) {
			return true;
		}
		try {
			Statement s = conn.createStatement();
			for (DbTrigger trigger : dbModel.getTriggers()) {
				String dropSql = dropTriggerSql(trigger);
				log.info("ddl : {}", dropSql);
				s.addBatch(dropSql);
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-drop-triggers").withCause(e).get();
		}
	}

	@Override
	public void createFunctions(DbModel dbModel) {
		if (dbModel.getFunctions() == null || dbModel.getFunctions().isEmpty()) {
			return;
		}
		try {
			Statement s = conn.createStatement();
			for (DbFunction function : dbModel.getFunctions()) {
				String dropSql = dropRoutineSql(function);
				log.info("ddl : {}", dropSql);
				s.executeUpdate(dropSql);
				String sql = createRoutineSql(function);
				log.info("ddl : {}", sql);
				s.execute(sql);
			}
		} catch (SQLException e) {
			throw Exceptions.server("failed-to-create-functions").withCause(e).get();
		}
	}

	@Override
	public void createFullTextIndexes(DbModel dbModel) {
		boolean hasCatalogs = dbModel.getFullTextCatalogs() != null && !dbModel.getFullTextCatalogs().isEmpty();
		boolean hasIndexes = dbModel.getFullTextIndexes() != null && !dbModel.getFullTextIndexes().isEmpty();
		if (!hasCatalogs && !hasIndexes) {
			return;
		}
		try {
			Statement s = conn.createStatement();
			if (hasCatalogs) {
				boolean defaultEmitted = false;
				for (DbFullTextCatalog catalog : dbModel.getFullTextCatalogs()) {
					boolean asDefault = catalog.isDefault() && !defaultEmitted;
					if (asDefault) {
						defaultEmitted = true;
					}
					String sql = createFullTextCatalogSql(catalog, asDefault);
					log.info("ddl : {}", sql);
					s.execute(sql);
				}
			}
			if (hasIndexes) {
				for (DbFullTextIndex index : dbModel.getFullTextIndexes()) {
					String dropSql = dropFullTextIndexSql(index);
					log.info("ddl : {}", dropSql);
					s.execute(dropSql);
					String createSql = createFullTextIndexSql(index);
					log.info("ddl : {}", createSql);
					s.execute(createSql);
				}
			}
		} catch (SQLException e) {
			throw Exceptions.server("failed-to-create-fulltext-indexes").withCause(e).get();
		}
	}

	static String createFullTextCatalogSql(DbFullTextCatalog catalog, boolean asDefault) {
		StringBuilder sb = new StringBuilder();
		sb.append("IF NOT EXISTS (SELECT 1 FROM sys.fulltext_catalogs WHERE name = N'");
		sb.append(catalog.getName().replace("'", "''"));
		sb.append("') CREATE FULLTEXT CATALOG ");
		appendBracketQuoted(sb, catalog.getName());
		if (asDefault) {
			sb.append(" AS DEFAULT");
		}
		return sb.toString();
	}

	static String dropFullTextIndexSql(DbFullTextIndex index) {
		StringBuilder sb = new StringBuilder();
		sb.append("IF EXISTS (SELECT 1 FROM sys.fulltext_indexes WHERE object_id = OBJECT_ID(N'");
		sb.append(index.qualifiedTableName().replace("'", "''"));
		sb.append("')) DROP FULLTEXT INDEX ON ");
		sb.append(CommonHelpers.bracketQuotedQualified(index.getSchemaName(), index.getTableName()));
		return sb.toString();
	}

	static String createFullTextIndexSql(DbFullTextIndex index) {
		StringBuilder sb = new StringBuilder("CREATE FULLTEXT INDEX ON ");
		sb.append(CommonHelpers.bracketQuotedQualified(index.getSchemaName(), index.getTableName()));
		sb.append(" (");
		Iterator<DbFullTextIndex.Column> it = index.getColumns().iterator();
		while (it.hasNext()) {
			DbFullTextIndex.Column col = it.next();
			appendBracketQuoted(sb, col.getColumnName());
			if (StringUtils.hasText(col.getTypeColumnName())) {
				sb.append(" TYPE COLUMN ");
				appendBracketQuoted(sb, col.getTypeColumnName());
			}
			if (col.getLanguageId() != null) {
				sb.append(" LANGUAGE ").append(col.getLanguageId());
			}
			if (it.hasNext()) {
				sb.append(", ");
			}
		}
		sb.append(") KEY INDEX ");
		appendBracketQuoted(sb, index.getUniqueIndexName());
		if (StringUtils.hasText(index.getCatalogName())) {
			sb.append(" ON ");
			appendBracketQuoted(sb, index.getCatalogName());
		}
		sb.append(" WITH CHANGE_TRACKING ");
		String changeTracking = index.getChangeTracking();
		if (!StringUtils.hasText(changeTracking)) {
			changeTracking = "AUTO";
		}
		sb.append(changeTracking);
		String stoplist = index.getStoplistName();
		if (!StringUtils.hasText(stoplist) || "OFF".equalsIgnoreCase(stoplist)) {
			sb.append(", STOPLIST = OFF");
		} else if ("SYSTEM".equalsIgnoreCase(stoplist)) {
			sb.append(", STOPLIST = SYSTEM");
		} else {
			sb.append(", STOPLIST = ");
			appendBracketQuoted(sb, stoplist);
		}
		return sb.toString();
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
					throw Exceptions.server("view-definition-permission-required")
						.withExtra("requiredPermission", "VIEW DEFINITION")
						.withExtra("objectType", "trigger")
						.withExtra("objectName", trigger.getName())
						.withExtra("tableName", trigger.qualifiedTableName())
						.get();
				}
				log.info("ddl : {}", createSql);
				s.execute(createSql);
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
				s.addBatch("DROP TYPE IF EXISTS " + CommonHelpers.bracketQuotedQualified(domainName));
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-drop-domains").withCause(e).get();
		}
	}

	static String dropRoutineSql(DbFunction function) {
		String kind = DbFunction.TYPE_PROCEDURE.equalsIgnoreCase(function.resolvedRoutineType()) ? "PROCEDURE" : "FUNCTION";
		return "DROP " + kind + " IF EXISTS " + CommonHelpers.bracketQuotedQualified(function.getSchema(), function.getName());
	}

	static String createRoutineSql(DbFunction function) {
		String def = function.getDefinition();
		if (def == null || def.isBlank()) {
			throw Exceptions.server("view-definition-permission-required")
				.withExtra("requiredPermission", "VIEW DEFINITION")
				.withExtra("objectType", function.resolvedRoutineType().toLowerCase())
				.withExtra("objectName", function.qualifiedName())
				.get();
		}
		return def.trim();
	}

	static String dropTriggerSql(DbTrigger trigger) {
		if (trigger.isDatabaseLevel()) {
			return "DROP TRIGGER IF EXISTS " + bracketQuoted(trigger.getName()) + " ON DATABASE";
		}
		return "DROP TRIGGER IF EXISTS " + CommonHelpers.bracketQuotedQualified(trigger.getSchema(), trigger.getName());
	}

	static String dropViewSql(String qualifiedName) {
		return "DROP VIEW IF EXISTS " + CommonHelpers.bracketQuotedQualified(qualifiedName);
	}

	static String createViewSql(DbView view) {
		String def = view.getDefinition();
		String sql = "CREATE VIEW " + CommonHelpers.bracketQuotedQualified(view.getSchema(), view.getName()) + " AS " + def;
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
                    statements.add("DROP TABLE IF EXISTS " + CommonHelpers.bracketQuotedQualified(operation.getQualifiedName()) + ";");
                }
                break;
            case MODIFY:
                // For MODIFY, we drop and recreate the table
                if (operation.getOldTable() != null) {
                    statements.add("DROP TABLE IF EXISTS " + CommonHelpers.bracketQuotedQualified(operation.getQualifiedName()) + ";");
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
		String quotedTableName = CommonHelpers.bracketQuotedQualified(tableName);
		boolean hasClustedIndex = dbModel.indexesForTable(tableName)
				.values().stream().map(ii -> "CLUSTERED".equals(ii.getIndexType())).reduce(Boolean.FALSE, (a, v) -> a || v);
		
		StringBuilder sb = new StringBuilder("CREATE TABLE ").append(quotedTableName).append(" (").append(System.lineSeparator());
		DbColumn[] columns = table.orderedColumns();
		int index = 0;
		for(DbColumn c : columns){
			sb.append("  ");
			appendBracketQuoted(sb, c.getName());
			sb.append(" ").append(columnTypeSql(c));
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
			if(index < columns.length - 1){
				sb.append(",").append(System.lineSeparator());
			}
			index++;
		}
		if(table.getPkColumnNames().size() > 0){
			sb.append(",").append(System.lineSeparator());
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
				appendBracketQuoted(sb, cName);
				if(cIt.hasNext()){
					sb.append(", ");
				}
			}
			sb.append(")");
		}
		/* FKs are separate DiffOps / enableContraints — omit from CREATE TABLE */
		sb.append(System.lineSeparator()).append(");");
		log.info("create script for {} : {}", tableName, sb.toString());
        return sb.toString();
    }
    
    private List<String> generateColumnSql(DbColumnDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        String tableName = CommonHelpers.bracketQuotedQualified(operation.getTableQualifiedName());
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
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD ");
        appendBracketQuoted(sb, column.getName());
        sb.append(" ").append(columnType(column.getDataType(), column.getMaxLength(), column.getNumPrecision(), column.getNumScale()));
        
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
                .append(" ALTER COLUMN ");
            appendBracketQuoted(sb, newColumn.getName());
            sb.append(" ")
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
                    statements.add("ALTER TABLE " + CommonHelpers.bracketQuotedQualified(ref.srcQualifiedName()) + " DROP CONSTRAINT " + bracketQuoted(ref.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldReference() != null) {
                    ReferenceInfo ref = operation.getOldReference();
                    statements.add("ALTER TABLE " + CommonHelpers.bracketQuotedQualified(ref.srcQualifiedName()) + " DROP CONSTRAINT " + bracketQuoted(ref.getConstraintName()) + ";");
                }
                if (operation.getNewReference() != null) {
                    statements.add(generateAddForeignKeySql(operation.getNewReference()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateAddForeignKeySql(ReferenceInfo ref) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(CommonHelpers.bracketQuotedQualified(ref.srcQualifiedName()))
            .append(" WITH NOCHECK ADD CONSTRAINT ");
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
        
        sb.append(") REFERENCES ").append(CommonHelpers.bracketQuotedQualified(ref.refQualifiedName())).append(" (");
        cIt = ref.getRefColumnNames().iterator();
        while (cIt.hasNext()) {
            String cName = cIt.next();
            sb.append("[").append(cName).append("]");
            if (cIt.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append(")");
        
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
                    statements.add("ALTER TABLE " + CommonHelpers.bracketQuotedQualified(constraint.qualifiedTableName()) + " DROP CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    ContraintInfo constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + CommonHelpers.bracketQuotedQualified(constraint.qualifiedTableName()) + " DROP CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + ";");
                }
                if (operation.getNewConstraint() != null) {
                    statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateAddUniqueConstraintSql(ContraintInfo constraint) {
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(CommonHelpers.bracketQuotedQualified(constraint.qualifiedTableName()))
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
                    addCheckConstraintCreateSql(statements, operation.getNewConstraint());
                }
                break;
            case DROP:
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + CommonHelpers.bracketQuotedQualified(constraint.qualifiedTableName()) + " DROP CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldConstraint() != null) {
                    CheckConstraint constraint = operation.getOldConstraint();
                    statements.add("ALTER TABLE " + CommonHelpers.bracketQuotedQualified(constraint.qualifiedTableName()) + " DROP CONSTRAINT " + bracketQuoted(constraint.getConstraintName()) + ";");
                }
                if (operation.getNewConstraint() != null) {
                    addCheckConstraintCreateSql(statements, operation.getNewConstraint());
                }
                break;
        }
        
        return statements;
    }

	private void addCheckConstraintCreateSql(List<String> statements, CheckConstraint constraint) {
		if (StringUtils.isEmptyOrNull(constraint.getCondef())) {
			throw Exceptions.server("view-definition-permission-required")
					.withExtra("requiredPermission", "VIEW DEFINITION")
					.withExtra("objectType", "check-constraint")
					.withExtra("objectName", constraint.getConstraintName())
					.withExtra("tableName", constraint.qualifiedTableName())
					.get();
		}
		statements.add("ALTER TABLE " + CommonHelpers.bracketQuotedQualified(constraint.qualifiedTableName())
				+ " WITH CHECK ADD CONSTRAINT " + bracketQuoted(constraint.getConstraintName())
				+ " CHECK " + constraint.getCondef() + ";");
	}
    
    private List<String> generateIndexSql(DbIndexDiffOp operation, DiffOpType opType) {
        List<String> statements = new ArrayList<>();
        String quotedTable = CommonHelpers.bracketQuotedQualified(operation.getTableQualifiedName());
        
        switch (opType) {
            case CREATE:
                if (operation.getNewIndex() != null) {
                    statements.add(generateCreateIndexSql(operation.getNewIndex(), quotedTable));
                }
                break;
            case DROP:
                if (operation.getOldIndex() != null) {
                    IndexInfo index = operation.getOldIndex();
                    statements.add("DROP INDEX " + index.getIndexName() + " ON " + quotedTable + ";");
                }
                break;
            case MODIFY:
                // Drop old and create new
                if (operation.getOldIndex() != null) {
                    IndexInfo index = operation.getOldIndex();
                    String qualifiedIndexName = CommonHelpers.qualifiedName(index.getSchemaName(), index.getIndexName());
                    statements.add("DROP INDEX " + qualifiedIndexName + " ON " + quotedTable + ";");
                }
                if (operation.getNewIndex() != null) {
                    statements.add(generateCreateIndexSql(operation.getNewIndex(), quotedTable));
                }
                break;
        }
        
        return statements;
    }
    
    private String generateCreateIndexSql(IndexInfo index, String tableQualifiedName) {
        return createIndexSql(index, tableQualifiedName);
    }

	/**
	 * Builds CREATE INDEX DDL. Handles rowstore, XML, and columnstore indexes.
	 * CLUSTERED COLUMNSTORE has no column list; NONCLUSTERED COLUMNSTORE lists columns
	 * in the index key (not INCLUDE). Secondary XML indexes use USING / FOR.
	 */
	static String createIndexSql(IndexInfo index, String quotedTableName) {
		String type = index.getIndexType() != null ? index.getIndexType().trim() : "NONCLUSTERED";
		String typeUpper = type.toUpperCase();
		boolean columnstore = typeUpper.contains("COLUMNSTORE");
		boolean clusteredColumnstore = "CLUSTERED COLUMNSTORE".equals(typeUpper);
		boolean xml = "XML".equals(typeUpper);

		if (xml) {
			return createXmlIndexSql(index, quotedTableName);
		}

		StringBuilder sb = new StringBuilder("CREATE ");
		if (index.isUnique() && !columnstore) {
			sb.append("UNIQUE ");
		}
		sb.append(type).append(" INDEX ");
		appendBracketQuoted(sb, index.getIndexName());
		sb.append(" ON ").append(quotedTableName);

		if (clusteredColumnstore) {
			sb.append(";");
			return sb.toString();
		}

		LinkedList<String> keyColumns = new LinkedList<>();
		if (index.getColumns() != null) {
			keyColumns.addAll(index.getColumns());
		}
		if (columnstore && index.getExtraColumns() != null) {
			keyColumns.addAll(index.getExtraColumns());
		}

		sb.append(" (");
		Iterator<String> icIt = keyColumns.iterator();
		while (icIt.hasNext()) {
			appendBracketQuoted(sb, icIt.next());
			if (icIt.hasNext()) {
				sb.append(", ");
			}
		}
		sb.append(")");

		if (!columnstore && index.getExtraColumns() != null && !index.getExtraColumns().isEmpty()) {
			sb.append(" INCLUDE (");
			Iterator<String> xcIt = index.getExtraColumns().iterator();
			while (xcIt.hasNext()) {
				appendBracketQuoted(sb, xcIt.next());
				if (xcIt.hasNext()) {
					sb.append(", ");
				}
			}
			sb.append(")");
		}
		sb.append(";");
		return sb.toString();
	}

	static String createXmlIndexSql(IndexInfo index, String quotedTableName) {
		if (index.isSecondaryXmlIndex()) {
			if (StringUtils.isEmptyOrNull(index.getUsingXmlIndexName())) {
				throw Exceptions.server("failed-to-create-tables")
						.withExtra("reason", "secondary-xml-index-missing-primary")
						.withExtra("indexName", index.getIndexName())
						.withExtra("tableName", quotedTableName)
						.get();
			}
			StringBuilder sb = new StringBuilder("CREATE XML INDEX ");
			appendBracketQuoted(sb, index.getIndexName());
			sb.append(" ON ").append(quotedTableName).append(" (");
			appendXmlIndexColumns(sb, index);
			sb.append(") USING XML INDEX ");
			appendBracketQuoted(sb, index.getUsingXmlIndexName());
			sb.append(" FOR ").append(index.getXmlSecondaryType().trim().toUpperCase()).append(";");
			return sb.toString();
		}

		StringBuilder sb = new StringBuilder("CREATE PRIMARY XML INDEX ");
		appendBracketQuoted(sb, index.getIndexName());
		sb.append(" ON ").append(quotedTableName).append(" (");
		appendXmlIndexColumns(sb, index);
		sb.append(");");
		return sb.toString();
	}

	private static void appendXmlIndexColumns(StringBuilder sb, IndexInfo index) {
		LinkedList<String> keyColumns = index.getColumns() != null ? index.getColumns() : new LinkedList<>();
		Iterator<String> icIt = keyColumns.iterator();
		while (icIt.hasNext()) {
			appendBracketQuoted(sb, icIt.next());
			if (icIt.hasNext()) {
				sb.append(", ");
			}
		}
	}

	/** Primary XML before secondary XML; other indexes first. */
	static int xmlIndexCreateOrder(IndexInfo index) {
		if (!index.isXmlIndex()) {
			return 0;
		}
		return index.isSecondaryXmlIndex() ? 2 : 1;
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
