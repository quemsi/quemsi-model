package com.quemsi.model.flow.db.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.commons.util.StringUtils;
import com.quemsi.model.flow.db.DDLService;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
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
import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
@NoArgsConstructor
public class DDLServiceOracle implements DDLService {
	private static final Pattern NOT_NULL_CHECK_PATTERN = Pattern.compile(
		"^\\(?\\s*\"?([A-Za-z0-9_$#]+)\"?\\s+IS\\s+NOT\\s+NULL\\s*\\)?$",
		Pattern.CASE_INSENSITIVE);
	private Connection conn;

	@Override
	public boolean dropTables(String... tableNames) {
		try {
			Statement s = conn.createStatement();
			for (String tableName : tableNames) {
				s.addBatch("DROP TABLE " + tableName + " CASCADE CONSTRAINTS PURGE");
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-clear-tables").withCause(e).get();
		}
	}

	@Override
	public boolean dropSequences(String... sequenceNames) {
		try {
			Statement s = conn.createStatement();
			for (String sequenceName : sequenceNames) {
				s.addBatch("DROP SEQUENCE " + sequenceName);
			}
			s.executeBatch();
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-clear-sequences").withCause(e).get();
		}
	}

	@Override
	public boolean dropViews(String... viewNames) {
		try {
			Statement s = conn.createStatement();
			for (String viewName : viewNames) {
				try {
					s.executeUpdate("DROP VIEW " + viewName);
				} catch (SQLException e) {
					// ORA-00942: table or view does not exist
					if (e.getErrorCode() != 942) {
						throw e;
					}
				}
			}
			return true;
		} catch (Exception e) {
			throw Exceptions.server("failed-to-drop-views").withCause(e).get();
		}
	}

	public LinkedList<String> tables(Set<String> schemas) {
		try (PreparedStatement ps = conn.prepareStatement(CommonHelpers.addInParameter(DatasourceFactoryOracle.SQL_FOR_TABLES, schemas.size()))) {
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			LinkedList<String> tables = new LinkedList<>();
			while (rs.next()) {
				String schemaName = rs.getString("SCHEMA_NAME");
				String tableName = rs.getString("TABLE_NAME");
				tables.add(CommonHelpers.qualifiedName(schemaName, tableName));
			}
			return tables;
		} catch (Exception e) {
			throw Exceptions.server("unable-to-get-tables").withCause(e).get();
		}
	}

	public LinkedList<String> sequences(Set<String> schemas) {
		try (PreparedStatement ps = conn.prepareStatement(CommonHelpers.addInParameter(DatasourceFactoryOracle.SQL_FOR_SEQUENCES, schemas.size()))) {
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			LinkedList<String> seqs = new LinkedList<>();
			while (rs.next()) {
				String schemaName = rs.getString("SCHEMA_NAME");
				String sequenceName = rs.getString("SEQUENCE_NAME");
				seqs.add(CommonHelpers.qualifiedName(schemaName, sequenceName));
			}
			return seqs;
		} catch (Exception e) {
			throw Exceptions.server("unable-to-get-sequences").withCause(e).get();
		}
	}

	public LinkedList<String> constraintNames(Set<String> schemas) {
		try (PreparedStatement ps = conn.prepareStatement(CommonHelpers.addInParameter(DatasourceFactoryOracle.SQL_FOR_CONSTRAINT_NAMES, schemas.size()))) {
			CommonHelpers.consumeIndexed(schemas, 1, Exceptions.wrapBiConsumer((i, schema) -> ps.setString(i, schema)));
			ResultSet rs = ps.executeQuery();
			LinkedList<String> names = new LinkedList<>();
			while (rs.next()) {
				String schemaName = rs.getString("SCHEMA_NAME");
				String constraintName = rs.getString("CONSTRAINT_NAME");
				names.add(CommonHelpers.qualifiedName(schemaName, constraintName));
			}
			return names;
		} catch (Exception e) {
			throw Exceptions.server("unable-to-get-constraints").withCause(e).get();
		}
	}

	@Override
	public void disableConstraints(Set<ReferenceInfo> constraints) {
		for (ReferenceInfo refInfo : constraints) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ");
			sb.append(refInfo.srcQualifiedName()).append(" DISABLE CONSTRAINT ");
			appendQuoted(sb, refInfo.getConstraintName());
			try {
				String sql = sb.toString();
				log.info("disable constraint sql :{}", sql);
				Statement s = conn.createStatement();
				s.executeUpdate(sql);
			} catch (SQLException ignore) {
				log.info("ignored disable constraint " + refInfo.getConstraintName(), ignore);
			}
		}
	}

	@Override
	public void enableContraints(Set<ReferenceInfo> constraints) {
		for (ReferenceInfo refInfo : constraints) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ");
			sb.append(refInfo.srcQualifiedName()).append(" ENABLE CONSTRAINT ");
			appendQuoted(sb, refInfo.getConstraintName());
			try {
				String sql = sb.toString();
				log.info("enable constraint sql :{}", sql);
				Statement s = conn.createStatement();
				s.executeUpdate(sql);
			} catch (SQLException ignore) {
				log.info("ignored enable constraint " + refInfo.getConstraintName(), ignore);
			}
		}
	}

	private String columnType(String type, Integer maxLength, Integer precision, Integer scale) {
		String normalized = type == null ? "" : type.toLowerCase();
		if (Set.of("varchar", "varchar2", "nvarchar2", "char", "nchar").contains(normalized) && maxLength != null) {
			return normalized.toUpperCase() + "(" + maxLength + ")";
		}
		if (Set.of("number", "numeric", "decimal").contains(normalized)) {
			// Oracle NUMBER(*,0) has null DATA_PRECISION with DATA_SCALE=0; tools often display that as NUMBER(38,0).
			if (precision != null && scale != null) {
				return "NUMBER(" + precision + "," + scale + ")";
			}
			if (precision != null) {
				return "NUMBER(" + precision + ")";
			}
			if (scale != null) {
				return "NUMBER(*," + scale + ")";
			}
		}
		return type == null ? "VARCHAR2(4000)" : type.toUpperCase();
	}

	private StringBuilder escape(StringBuilder sb, String columnName) {
		if (needsQuoting(columnName)) {
			appendQuoted(sb, columnName);
		} else {
			sb.append(columnName);
		}
		return sb;
	}

	private boolean needsQuoting(String name) {
		return DatasourceFactoryOracle.RESERVED_KEYS.contains(name.toUpperCase())
			|| !name.equals(name.toUpperCase());
	}

	private static void appendQuoted(StringBuilder sb, String name) {
		sb.append('"').append(name.replace("\"", "\"\"")).append('"');
	}

	private static String quoted(String name) {
		StringBuilder sb = new StringBuilder();
		appendQuoted(sb, name);
		return sb.toString();
	}

	private static String sqlStringLiteral(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	/**
	 * Resolve indexes for a table by qualified table name, with fallback for backups
	 * keyed incorrectly by schema.indexName (pre-IndexInfo.qualifiedTableName fix).
	 */
	private Map<String, IndexInfo> indexesForTable(DbModel dbModel, String qualifiedTableName) {
		Map<String, IndexInfo> direct = dbModel.getIndexes() != null
			? dbModel.getIndexes().get(qualifiedTableName)
			: null;
		if (direct != null && !direct.isEmpty()) {
			return direct;
		}
		Map<String, IndexInfo> matched = new LinkedHashMap<>();
		if (dbModel.getIndexes() == null) {
			return matched;
		}
		for (Map<String, IndexInfo> byName : dbModel.getIndexes().values()) {
			if (byName == null) {
				continue;
			}
			for (IndexInfo idx : byName.values()) {
				if (idx != null && qualifiedTableName.equals(CommonHelpers.qualifiedName(idx.getSchemaName(), idx.getTableName()))) {
					matched.put(idx.getIndexName(), idx);
				}
			}
		}
		return matched;
	}

	private void appendColumnDefault(StringBuilder sb, DbColumn column) {
		if (column.getColumnDefault() != null) {
			sb.append(" DEFAULT ").append(StringUtils.trimSymetric(column.getColumnDefault(), "(", ")"));
		}
	}

	private String formatCheckConstraintCondition(String condef) {
		if (condef == null || condef.isBlank()) {
			return "()";
		}
		String trimmed = condef.trim();
		if (trimmed.regionMatches(true, 0, "CHECK", 0, 5)) {
			trimmed = trimmed.substring(5).trim();
		}
		if (trimmed.startsWith("(")) {
			return trimmed;
		}
		return "(" + trimmed + ")";
	}

	/**
	 * Oracle stores named NOT NULL constraints as CONSTRAINT_TYPE='C' with SEARCH_CONDITION
	 * like {@code "COL" IS NOT NULL}. Restore them as column-level named NOT NULL to avoid
	 * duplicate SYS_C* constraints from bare NOT NULL plus a CHECK rewrite.
	 */
	Optional<String> notNullColumnFromCheck(CheckConstraint checkConstraint) {
		if (checkConstraint == null || checkConstraint.getCondef() == null) {
			return Optional.empty();
		}
		Matcher matcher = NOT_NULL_CHECK_PATTERN.matcher(checkConstraint.getCondef().trim());
		if (!matcher.matches()) {
			return Optional.empty();
		}
		return Optional.of(matcher.group(1));
	}

	private Map<String, Map<String, CheckConstraint>> namedNotNullConstraints(DbModel dbModel) {
		Map<String, Map<String, CheckConstraint>> byTable = new HashMap<>();
		if (dbModel.getCheckConstraints() == null) {
			return byTable;
		}
		for (CheckConstraint checkConstraint : dbModel.getCheckConstraints()) {
			notNullColumnFromCheck(checkConstraint).ifPresent(columnName ->
				byTable.computeIfAbsent(checkConstraint.qualifiedTableName(), key -> new HashMap<>())
					.putIfAbsent(columnName.toUpperCase(Locale.ROOT), checkConstraint));
		}
		return byTable;
	}

	private void appendColumnNullability(StringBuilder sb, DbColumn column, CheckConstraint namedNotNull, boolean primaryKeyColumn) {
		if (namedNotNull != null) {
			sb.append(" CONSTRAINT ");
			appendQuoted(sb, namedNotNull.getConstraintName());
			sb.append(" NOT NULL");
		} else if (!column.isNullable() && !primaryKeyColumn) {
			// PK already enforces NOT NULL; bare NOT NULL would create a redundant SYS_C* constraint
			sb.append(" NOT NULL");
		}
	}

	private Set<String> primaryKeyColumnNamesUpper(DbTable table) {
		if (table.getPkColumnNames() == null || table.getPkColumnNames().isEmpty()) {
			return Set.of();
		}
		return table.getPkColumnNames().stream()
			.map(name -> name.toUpperCase(Locale.ROOT))
			.collect(Collectors.toSet());
	}

	@Override
	public void createTables(DbModel dbModel) {
		LinkedList<StringBuilder> scripts = new LinkedList<>();
		Set<String> existingTables = new HashSet<>(tables(dbModel.getSchemas()));
		Set<String> existingConstraints = new HashSet<>(constraintNames(dbModel.getSchemas()));
		Set<String> sequences = new HashSet<>(sequences(dbModel.getSchemas()));
		if (!dbModel.getSequences().isEmpty()) {
			for (DbSequence seq : dbModel.getSequences()) {
				if (sequences.contains(seq.qualifiedName())) {
					continue;
				}
				StringBuilder seqBuilder = new StringBuilder("CREATE SEQUENCE ");
				seqBuilder.append(seq.qualifiedName());
				seqBuilder.append(" START WITH ");
				if (seq.getLastValue() == null) {
					seqBuilder.append(seq.getStartValue());
				} else {
					seqBuilder.append(seq.getLastValue() + 1L);
				}
				seqBuilder.append(" INCREMENT BY ").append(seq.getIncrementBy());
				if (seq.getMinValue() != null && seq.getMinValue() > Long.MIN_VALUE) {
					seqBuilder.append(" MINVALUE ").append(seq.getMinValue());
				}
				if (seq.getMaxValue() != null && seq.getMaxValue() < Long.MAX_VALUE) {
					seqBuilder.append(" MAXVALUE ").append(seq.getMaxValue());
				}
				if (seq.isCycle()) {
					seqBuilder.append(" CYCLE");
				} else {
					seqBuilder.append(" NOCYCLE");
				}
				if (seq.getCacheSize() != null && seq.getCacheSize() > 0L) {
					seqBuilder.append(" CACHE ").append(seq.getCacheSize());
				} else {
					seqBuilder.append(" NOCACHE");
				}
				seqBuilder.append(";");
				log.info("sequence sql : {}", seqBuilder);
				scripts.add(seqBuilder);
			}
		}
		for (String tableName : dbModel.orderedTableNames()) {
			if (existingTables.contains(tableName)) {
				log.info("table {} already exists in schema {} skipping", tableName, dbModel.getSchemas());
				continue;
			}
			DbTable table = dbModel.findTable(tableName).orElseThrow();
			StringBuilder sb = new StringBuilder(generateCreateTableSql(table, dbModel));
			log.info("create script for {} : {}", tableName, sb);
			scripts.add(sb);
			Map<String, IndexInfo> indexes = indexesForTable(dbModel, tableName);
			DbColumn[] columns = table.orderedColumns();
			for (IndexInfo indCols : indexes.values()) {
				StringBuilder indBuilder = new StringBuilder("CREATE ");
				if (indCols.isUnique()) {
					indBuilder.append("UNIQUE ");
				}
				indBuilder.append("INDEX ");
				appendQuoted(indBuilder, indCols.getIndexName());
				indBuilder.append(" ON ");
				indBuilder.append(tableName).append(" (");
				appendColumnList(indBuilder, indCols.getColumns());
				indBuilder.append(")");
				log.info("index sql : {}", indBuilder);
				scripts.add(indBuilder);
			}
			if (table.getComment() != null && !table.getComment().isBlank()) {
				StringBuilder commentSb = new StringBuilder("COMMENT ON TABLE ").append(tableName)
					.append(" IS ").append(sqlStringLiteral(table.getComment()));
				scripts.add(commentSb);
			}
			for (DbColumn c : columns) {
				if (c.getComment() != null && !c.getComment().isBlank()) {
					StringBuilder commentSb = new StringBuilder("COMMENT ON COLUMN ").append(tableName).append(".");
					escape(commentSb, c.getName());
					commentSb.append(" IS ").append(sqlStringLiteral(c.getComment()));
					scripts.add(commentSb);
				}
			}
		}
		if (dbModel.getCircularIgnore() != null) {
			for (ReferenceInfo ref : dbModel.getCircularIgnore()) {
				if (existingConstraints.contains(ref.qualifiedConstraintName())) {
					log.info("circular FK {} already exists on {} skipping", ref.getConstraintName(), ref.srcQualifiedName());
					continue;
				}
				String fkSql = generateAddForeignKeySql(ref);
				log.info("deferred circular FK for {} : {}", ref.srcQualifiedName(), fkSql);
				scripts.add(new StringBuilder(fkSql));
			}
		}
		for (ContraintInfo contraintInfo : dbModel.getContraintInfos()) {
			if (existingConstraints.contains(contraintInfo.qualifiedConstraintName())) {
				log.info("unique constraint {} already exists on {} skipping", contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName());
				continue;
			}
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(contraintInfo.qualifiedTableName()).append(" ADD CONSTRAINT ");
			appendQuoted(sb, contraintInfo.getConstraintName());
			sb.append(" UNIQUE (");
			appendColumnList(sb, contraintInfo.getColumnNames());
			sb.append(")");
			log.info("create unique constraint {} for table {} : {}", contraintInfo.getConstraintName(), contraintInfo.qualifiedTableName(), sb);
			scripts.add(sb);
		}
		for (CheckConstraint checkConstraint : dbModel.getCheckConstraints()) {
			if (notNullColumnFromCheck(checkConstraint).isPresent()) {
				continue;
			}
			if (existingConstraints.contains(checkConstraint.qualifiedConstraintName())) {
				log.info("check constraint {} already exists on {} skipping", checkConstraint.getConstraintName(), checkConstraint.qualifiedTableName());
				continue;
			}
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(checkConstraint.qualifiedTableName()).append(" ADD CONSTRAINT ");
			appendQuoted(sb, checkConstraint.getConstraintName());
			sb.append(" CHECK ").append(formatCheckConstraintCondition(checkConstraint.getCondef()));
			log.info("create check constraint {} for table {} : {}", checkConstraint.getConstraintName(), checkConstraint.qualifiedTableName(), sb);
			scripts.add(sb);
		}
		try {
			for (String schema : dbModel.getSchemas()) {
				if (!checkSchema(schema)) {
					log.warn("Oracle schema/user {} does not exist; skipping schema creation", schema);
				}
			}
			Statement s = conn.createStatement();
			for (StringBuilder sb : scripts) {
				log.info("sql : {}", sb);
				s.executeUpdate(sb.toString());
			}
		} catch (SQLException e) {
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
		return "DROP VIEW " + qualifiedName;
	}

	static String createViewSql(DbView view) {
		String def = view.getDefinition();
		if (def != null) {
			def = def.trim();
			if (def.endsWith(";")) {
				def = def.substring(0, def.length() - 1);
			}
		}
		return "CREATE VIEW " + view.qualifiedName() + " AS " + def;
	}

	@Override
	public boolean checkSchema(String schema) throws SQLException {
		try (PreparedStatement ss = conn.prepareStatement(DatasourceFactoryOracle.SQL_FOR_SCHEMA)) {
			ss.setString(1, schema.toUpperCase());
			ResultSet rss = ss.executeQuery();
			return rss.next();
		}
	}

	@Override
	public void close() throws Exception {
		if (conn != null) {
			conn.close();
		}
	}

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
			case TABLE -> statements.addAll(generateTableSql((DbTableDiffOp) operation, opType, dbModel));
			case COLUMN -> statements.addAll(generateColumnSql((DbColumnDiffOp) operation, opType));
			case FOREIGN_KEY -> statements.addAll(generateForeignKeySql((DbForeignKeyDiffOp) operation, opType));
			case UNIQUE_CONSTRAINT -> statements.addAll(generateUniqueConstraintSql((DbUniqueConstraintDiffOp) operation, opType));
			case CHECK_CONSTRAINT -> statements.addAll(generateCheckConstraintSql((DbCheckConstraintDiffOp) operation, opType));
			case INDEX -> statements.addAll(generateIndexSql((DbIndexDiffOp) operation, opType));
			case SEQUENCE -> statements.addAll(generateSequenceSql((DbSequenceDiffOp) operation, opType));
			case VIEW -> statements.addAll(generateViewSql((DbViewDiffOp) operation, opType));
			default -> {
			}
		}
		return statements;
	}

	private List<String> generateViewSql(DbViewDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewView() != null) {
					statements.add(createViewSql(operation.getNewView()));
				}
			}
			case DROP -> statements.add(dropViewSql(operation.getQualifiedName()));
			case MODIFY -> {
				statements.add(dropViewSql(operation.getQualifiedName()));
				if (operation.getNewView() != null) {
					statements.add(createViewSql(operation.getNewView()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private List<String> generateTableSql(DbTableDiffOp operation, DiffOpType opType, DbModel dbModel) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewTable() != null) {
					statements.add(generateCreateTableSql(operation.getNewTable(), dbModel));
				}
			}
			case DROP -> {
				if (operation.getOldTable() != null) {
					statements.add("DROP TABLE " + operation.getQualifiedName() + " CASCADE CONSTRAINTS PURGE;");
				}
			}
			case MODIFY -> {
				if (operation.getOldTable() != null) {
					statements.add("DROP TABLE " + operation.getQualifiedName() + " CASCADE CONSTRAINTS PURGE;");
				}
				if (operation.getNewTable() != null) {
					statements.add(generateCreateTableSql(operation.getNewTable(), dbModel));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateCreateTableSql(DbTable table, DbModel dbModel) {
		String tableName = table.qualifiedName();
		Map<String, List<ReferenceInfo>> tableReferences = dbModel.getReferenceInfos().stream()
			.collect(Collectors.groupingBy(ReferenceInfo::srcQualifiedName));
		Map<String, CheckConstraint> namedNotNulls = namedNotNullConstraints(dbModel)
			.getOrDefault(tableName, Map.of());
		Set<String> pkColumns = primaryKeyColumnNamesUpper(table);
		List<ReferenceInfo> foreignKeys = tableReferences.getOrDefault(tableName, List.of()).stream()
			.filter(ref -> !isCircularIgnored(dbModel, ref))
			.toList();
		boolean hasPrimaryKey = table.getPkColumnNames() != null && !table.getPkColumnNames().isEmpty();

		StringBuilder sb = new StringBuilder("CREATE TABLE ").append(tableName).append(" (").append(System.lineSeparator());
		boolean firstElement = true;
		for (DbColumn c : table.orderedColumns()) {
			if (!firstElement) {
				sb.append(",").append(System.lineSeparator());
			}
			firstElement = false;
			sb.append("  ");
			escape(sb, c.getName()).append(" ").append(columnType(c.getDataType(), c.getMaxLength(), c.getNumPrecision(), c.getNumScale()));
			appendColumnDefault(sb, c);
			String columnKey = c.getName().toUpperCase(Locale.ROOT);
			appendColumnNullability(sb, c, namedNotNulls.get(columnKey), pkColumns.contains(columnKey));
		}
		if (hasPrimaryKey) {
			if (!firstElement) {
				sb.append(",").append(System.lineSeparator());
			}
			firstElement = false;
			sb.append("  CONSTRAINT ");
			appendQuoted(sb, table.getPkConstraintName());
			sb.append(" PRIMARY KEY (");
			appendColumnList(sb, table.getPkColumnNames());
			sb.append(")");
		}
		for (ReferenceInfo ref : foreignKeys) {
			if (!firstElement) {
				sb.append(",").append(System.lineSeparator());
			}
			firstElement = false;
			sb.append("  CONSTRAINT ");
			appendQuoted(sb, ref.getConstraintName());
			sb.append(" FOREIGN KEY (");
			appendColumnList(sb, ref.getSrcColumnNames());
			sb.append(") REFERENCES ").append(ref.refQualifiedName()).append(" (");
			appendColumnList(sb, ref.getRefColumnNames());
			sb.append(")");
		}
		sb.append(System.lineSeparator()).append(")");
		return sb.toString();
	}

	private boolean isCircularIgnored(DbModel dbModel, ReferenceInfo ref) {
		return dbModel.getCircularIgnore() != null && dbModel.getCircularIgnore().contains(ref);
	}

	private void appendColumnList(StringBuilder sb, Iterable<String> columnNames) {
		Iterator<String> cIt = columnNames.iterator();
		while (cIt.hasNext()) {
			String cName = cIt.next();
			if (needsQuoting(cName)) {
				appendQuoted(sb, cName);
			} else {
				sb.append(cName);
			}
			if (cIt.hasNext()) {
				sb.append(", ");
			}
		}
	}

	private List<String> generateColumnSql(DbColumnDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		String tableName = operation.getTableQualifiedName();
		String columnName = operation.getColumnName();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewColumn() != null) {
					statements.add(generateAddColumnSql(tableName, operation.getNewColumn()));
				}
			}
			case DROP -> statements.add("ALTER TABLE " + tableName + " DROP COLUMN " + quoted(columnName) + ";");
			case MODIFY -> {
				if (operation.getOldColumn() != null && operation.getNewColumn() != null) {
					statements.addAll(generateModifyColumnSql(tableName, operation.getOldColumn(), operation.getNewColumn()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateAddColumnSql(String tableName, DbColumn column) {
		StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" ADD (");
		escape(sb, column.getName()).append(" ").append(columnType(column.getDataType(), column.getMaxLength(), column.getNumPrecision(), column.getNumScale()));
		appendColumnDefault(sb, column);
		if (!column.isNullable()) {
			sb.append(" NOT NULL");
		}
		sb.append(")");
		return sb.toString();
	}

	private List<String> generateModifyColumnSql(String tableName, DbColumn oldColumn, DbColumn newColumn) {
		List<String> statements = new ArrayList<>();
		if (!Objects.equals(oldColumn.getColumnType(), newColumn.getColumnType())
			|| !Objects.equals(oldColumn.getMaxLength(), newColumn.getMaxLength())
			|| !Objects.equals(oldColumn.getNumPrecision(), newColumn.getNumPrecision())
			|| !Objects.equals(oldColumn.getNumScale(), newColumn.getNumScale())
			|| oldColumn.isNullable() != newColumn.isNullable()) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(tableName).append(" MODIFY (");
			escape(sb, newColumn.getName()).append(" ").append(columnType(newColumn.getDataType(), newColumn.getMaxLength(), newColumn.getNumPrecision(), newColumn.getNumScale()));
			if (!newColumn.isNullable()) {
				sb.append(" NOT NULL");
			}
			sb.append(")");
			statements.add(sb.toString());
		}
		if (!Objects.equals(oldColumn.getColumnDefault(), newColumn.getColumnDefault())) {
			if (newColumn.getColumnDefault() == null) {
				statements.add("ALTER TABLE " + tableName + " MODIFY (" + quoted(newColumn.getName()) + " DEFAULT NULL)");
			} else {
				statements.add("ALTER TABLE " + tableName + " MODIFY (" + quoted(newColumn.getName()) + " DEFAULT "
					+ StringUtils.trimSymetric(newColumn.getColumnDefault(), "(", ")") + ")");
			}
		}
		return statements;
	}

	private List<String> generateForeignKeySql(DbForeignKeyDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewReference() != null) {
					statements.add(generateAddForeignKeySql(operation.getNewReference()));
				}
			}
			case DROP -> {
				if (operation.getOldReference() != null) {
					ReferenceInfo ref = operation.getOldReference();
					statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP CONSTRAINT " + quoted(ref.getConstraintName()) + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldReference() != null) {
					ReferenceInfo ref = operation.getOldReference();
					statements.add("ALTER TABLE " + ref.srcQualifiedName() + " DROP CONSTRAINT " + quoted(ref.getConstraintName()) + ";");
				}
				if (operation.getNewReference() != null) {
					statements.add(generateAddForeignKeySql(operation.getNewReference()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateAddForeignKeySql(ReferenceInfo ref) {
		StringBuilder sb = new StringBuilder("ALTER TABLE ").append(ref.srcQualifiedName())
			.append(" ADD CONSTRAINT ");
		appendQuoted(sb, ref.getConstraintName());
		sb.append(" FOREIGN KEY (");
		appendColumnList(sb, ref.getSrcColumnNames());
		sb.append(") REFERENCES ").append(ref.refQualifiedName()).append(" (");
		appendColumnList(sb, ref.getRefColumnNames());
		sb.append(")");
		return sb.toString();
	}

	private List<String> generateUniqueConstraintSql(DbUniqueConstraintDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewConstraint() != null) {
					statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
				}
			}
			case DROP -> {
				if (operation.getOldConstraint() != null) {
					ContraintInfo constraint = operation.getOldConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + quoted(constraint.getConstraintName()) + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldConstraint() != null) {
					ContraintInfo constraint = operation.getOldConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + quoted(constraint.getConstraintName()) + ";");
				}
				if (operation.getNewConstraint() != null) {
					statements.add(generateAddUniqueConstraintSql(operation.getNewConstraint()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateAddUniqueConstraintSql(ContraintInfo constraint) {
		StringBuilder sb = new StringBuilder("ALTER TABLE ").append(constraint.qualifiedTableName())
			.append(" ADD CONSTRAINT ");
		appendQuoted(sb, constraint.getConstraintName());
		sb.append(" UNIQUE (");
		appendColumnList(sb, constraint.getColumnNames());
		sb.append(")");
		return sb.toString();
	}

	private List<String> generateCheckConstraintSql(DbCheckConstraintDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewConstraint() != null) {
					statements.add(generateAddCheckOrNotNullConstraintSql(operation.getNewConstraint()));
				}
			}
			case DROP -> {
				if (operation.getOldConstraint() != null) {
					CheckConstraint constraint = operation.getOldConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + quoted(constraint.getConstraintName()) + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldConstraint() != null) {
					CheckConstraint constraint = operation.getOldConstraint();
					statements.add("ALTER TABLE " + constraint.qualifiedTableName() + " DROP CONSTRAINT " + quoted(constraint.getConstraintName()) + ";");
				}
				if (operation.getNewConstraint() != null) {
					statements.add(generateAddCheckOrNotNullConstraintSql(operation.getNewConstraint()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateAddCheckOrNotNullConstraintSql(CheckConstraint constraint) {
		Optional<String> notNullColumn = notNullColumnFromCheck(constraint);
		if (notNullColumn.isPresent()) {
			StringBuilder sb = new StringBuilder("ALTER TABLE ").append(constraint.qualifiedTableName())
				.append(" MODIFY (");
			escape(sb, notNullColumn.get());
			sb.append(" CONSTRAINT ");
			appendQuoted(sb, constraint.getConstraintName());
			sb.append(" NOT NULL);");
			return sb.toString();
		}
		return "ALTER TABLE " + constraint.qualifiedTableName() + " ADD CONSTRAINT " + quoted(constraint.getConstraintName())
			+ " CHECK " + formatCheckConstraintCondition(constraint.getCondef()) + ";";
	}

	private List<String> generateIndexSql(DbIndexDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewIndex() != null) {
					statements.add(generateCreateIndexSql(operation.getNewIndex(), operation.getTableQualifiedName()));
				}
			}
			case DROP -> {
				if (operation.getOldIndex() != null) {
					statements.add("DROP INDEX " + operation.getOldIndex().getIndexName() + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldIndex() != null) {
					statements.add("DROP INDEX " + operation.getOldIndex().getIndexName() + ";");
				}
				if (operation.getNewIndex() != null) {
					statements.add(generateCreateIndexSql(operation.getNewIndex(), operation.getTableQualifiedName()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateCreateIndexSql(IndexInfo index, String tableQualifiedName) {
		StringBuilder sb = new StringBuilder("CREATE ");
		if (index.isUnique()) {
			sb.append("UNIQUE ");
		}
		sb.append("INDEX ").append(quoted(index.getIndexName())).append(" ON ").append(tableQualifiedName).append(" (");
		appendColumnList(sb, index.getColumns());
		sb.append(")");
		return sb.toString();
	}

	private List<String> generateSequenceSql(DbSequenceDiffOp operation, DiffOpType opType) {
		List<String> statements = new ArrayList<>();
		switch (opType) {
			case CREATE -> {
				if (operation.getNewSequence() != null) {
					statements.add(generateCreateSequenceSql(operation.getNewSequence()));
				}
			}
			case DROP -> {
				if (operation.getOldSequence() != null) {
					statements.add("DROP SEQUENCE " + operation.getQualifiedName() + ";");
				}
			}
			case MODIFY -> {
				if (operation.getOldSequence() != null && operation.getNewSequence() != null) {
					statements.addAll(generateAlterSequenceSql(operation.getOldSequence(), operation.getNewSequence()));
				}
			}
			default -> {
			}
		}
		return statements;
	}

	private String generateCreateSequenceSql(DbSequence seq) {
		StringBuilder sb = new StringBuilder("CREATE SEQUENCE ");
		sb.append(seq.qualifiedName());
		sb.append(" START WITH ");
		if (seq.getLastValue() == null) {
			sb.append(seq.getStartValue());
		} else {
			sb.append(seq.getLastValue() + 1L);
		}
		sb.append(" INCREMENT BY ").append(seq.getIncrementBy());
		if (seq.getMinValue() != null && seq.getMinValue() > Long.MIN_VALUE) {
			sb.append(" MINVALUE ").append(seq.getMinValue());
		}
		if (seq.getMaxValue() != null && seq.getMaxValue() < Long.MAX_VALUE) {
			sb.append(" MAXVALUE ").append(seq.getMaxValue());
		}
		if (seq.isCycle()) {
			sb.append(" CYCLE");
		} else {
			sb.append(" NOCYCLE");
		}
		if (seq.getCacheSize() != null && seq.getCacheSize() > 0L) {
			sb.append(" CACHE ").append(seq.getCacheSize());
		} else {
			sb.append(" NOCACHE");
		}
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
			}
			hasChanges = true;
		}
		if (!Objects.equals(oldSeq.getMaxValue(), newSeq.getMaxValue())) {
			if (newSeq.getMaxValue() != null && newSeq.getMaxValue() < Long.MAX_VALUE) {
				sb.append(" MAXVALUE ").append(newSeq.getMaxValue());
			}
			hasChanges = true;
		}
		if (!Objects.equals(oldSeq.getLastValue(), newSeq.getLastValue())
			|| !Objects.equals(oldSeq.getStartValue(), newSeq.getStartValue())) {
			if (newSeq.getLastValue() != null) {
				sb.append(" RESTART START WITH ").append(newSeq.getLastValue() + 1L);
			} else if (newSeq.getStartValue() != null) {
				sb.append(" RESTART START WITH ").append(newSeq.getStartValue());
			}
			hasChanges = true;
		}
		if (!Objects.equals(oldSeq.getCacheSize(), newSeq.getCacheSize())) {
			if (newSeq.getCacheSize() != null && newSeq.getCacheSize() > 0L) {
				sb.append(" CACHE ").append(newSeq.getCacheSize());
			} else {
				sb.append(" NOCACHE");
			}
			hasChanges = true;
		}
		if (oldSeq.isCycle() != newSeq.isCycle()) {
			sb.append(newSeq.isCycle() ? " CYCLE" : " NOCYCLE");
			hasChanges = true;
		}
		if (hasChanges) {
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
