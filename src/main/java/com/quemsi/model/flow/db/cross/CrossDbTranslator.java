package com.quemsi.model.flow.db.cross;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.util.CommonHelpers;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbFunction;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbModel.CheckConstraint;
import com.quemsi.model.flow.db.sql.DbModel.ContraintInfo;
import com.quemsi.model.flow.db.sql.DbModel.IndexInfo;
import com.quemsi.model.flow.db.sql.DbModel.ReferenceInfo;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.db.sql.DbTrigger;
import com.quemsi.model.flow.db.sql.DbView;

/**
 * Rewrites an in-memory {@link DbModel} from one SQL engine onto another.
 * The backup archive is not modified.
 */
public final class CrossDbTranslator {
	private static final Map<DatasourceType, SourceTypeReader> READERS = Map.of(
		DatasourceType.SQLSERVER, new SqlServerTypeReader(),
		DatasourceType.POSTGRES, new PostgresTypeReader(),
		DatasourceType.MYSQL, new MysqlTypeReader(),
		DatasourceType.ORACLE, new OracleTypeReader()
	);
	private static final Map<DatasourceType, TargetTypeWriter> WRITERS = Map.of(
		DatasourceType.SQLSERVER, new SqlServerTypeWriter(),
		DatasourceType.POSTGRES, new PostgresTypeWriter(),
		DatasourceType.MYSQL, new MysqlTypeWriter(),
		DatasourceType.ORACLE, new OracleTypeWriter()
	);

	private CrossDbTranslator() {
	}

	public static boolean isSql(DatasourceType type) {
		return type == DatasourceType.MYSQL || type == DatasourceType.POSTGRES
			|| type == DatasourceType.SQLSERVER || type == DatasourceType.ORACLE;
	}

	public static SourceTypeReader reader(DatasourceType type) {
		SourceTypeReader reader = READERS.get(type);
		if (reader == null) {
			throw Exceptions.badRequest("unsupported-source-type-for-rdbms-target").withExtra("sourceType", type).get();
		}
		return reader;
	}

	public static TargetTypeWriter writer(DatasourceType type) {
		TargetTypeWriter writer = WRITERS.get(type);
		if (writer == null) {
			throw Exceptions.badRequest("unsupported-source-type-for-rdbms-target").withExtra("targetType", type).get();
		}
		return writer;
	}

	public static CrossDbPlan translate(DbModel model, DatasourceType target) {
		DatasourceType source = DatasourceType.valueOf(model.getSourceType());
		if (!isSql(source) || !isSql(target) || source == target) {
			throw Exceptions.badRequest("unsupported-source-type-for-rdbms-target")
				.withExtra("sourceType", source)
				.withExtra("targetType", target)
				.get();
		}
		CrossDbReport report = new CrossDbReport();
		CrossDbPlan plan = new CrossDbPlan(source, target, report);
		if (target == DatasourceType.POSTGRES) {
			report.warnOnce("postgres-case", "Quoted identifiers are case-sensitive on Postgres");
		}
		IdentityHashMap<DbTable, String> archiveNames = snapshotArchiveNames(model);
		layoutSchemas(model, source, target, report);
		SourceTypeReader reader = reader(source);
		TargetTypeWriter writer = writer(target);
		List<DbTable> empty = new ArrayList<>();
		for (DbTable table : new ArrayList<>(model.getTables().values())) {
			translateTable(model, table, reader, writer, plan, report, source, target);
			if (table.getColumns().isEmpty()) {
				empty.add(table);
			} else {
				plan.putArchiveName(table.qualifiedName(), archiveNames.getOrDefault(table, table.qualifiedName()));
			}
		}
		for (DbTable table : empty) {
			report.warn("omitted table " + archiveNames.getOrDefault(table, table.getName()) + " because it has no portable columns");
			model.getTables().entrySet().removeIf(entry -> entry.getValue() == table);
			removeTableObjects(model, table.qualifiedName());
		}
		dropBrokenKeys(model, report);
		rewriteIndexes(model, writer, report);
		filterNullDistinctUniqueKeys(model, target, report);
		clearEngineObjects(model, report);
		int details = report.size();
		report.warn("cross-db restore completed with " + details + " warning(s)");
		return plan;
	}

	private static IdentityHashMap<DbTable, String> snapshotArchiveNames(DbModel model) {
		IdentityHashMap<DbTable, String> names = new IdentityHashMap<>();
		if (model.getTables() != null) {
			for (DbTable table : model.getTables().values()) {
				names.put(table, table.qualifiedName());
			}
		}
		return names;
	}

	private static void translateTable(DbModel model, DbTable table, SourceTypeReader reader, TargetTypeWriter writer,
			CrossDbPlan plan, CrossDbReport report, DatasourceType source, DatasourceType target) {
		DbColumn[] original = table.orderedColumns();
		List<DbColumn> kept = new ArrayList<>();
		List<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < original.length; i++) {
			DbColumn column = original[i];
			ColumnMapping mapping = reader.read(column, model, report);
			if (mapping.warning() != null) {
				report.warn(mapping.warning());
			}
			if (mapping.omit() || mapping.column() == null) {
				column.setOmitted(true);
				report.warn("omitted column " + table.qualifiedName() + "." + column.getName() + ": " + mapping.omitReason());
				continue;
			}
			if (mapping.column().getType() == PortableType.NUMERIC && mapping.column().getPrecision() != null
				&& mapping.column().getPrecision() > writer.maxNumericPrecision()) {
				column.setOmitted(true);
				report.warn("omitted column " + table.qualifiedName() + "." + column.getName()
					+ ": numeric precision " + mapping.column().getPrecision() + " exceeds " + writer.maxNumericPrecision());
				continue;
			}
			if (PortableColumns.expressionDefault(column)) {
				report.warn("omitted default on " + table.qualifiedName() + "." + column.getName() + ": "
					+ column.getColumnDefault().replaceAll("\\s+", " ").trim());
			}
			writer.apply(column, mapping.column(), report);
			applyCaseSensitiveCollation(column, source, target, report);
			kept.add(column);
			indexes.add(i);
		}
		table.getColumns().clear();
		int ordinal = 1;
		for (DbColumn column : kept) {
			column.setOrdinalPosition(ordinal++);
			table.addColumn(column);
		}
		plan.putSourceIndexes(table.qualifiedName(), indexes.stream().mapToInt(Integer::intValue).toArray());
		dropPartialPrimaryKey(table, report);
	}

	/**
	 * Postgres and Oracle compare varchar as case-sensitive. SQL Server unique keys use the column
	 * collation, so a case-insensitive database collation would reject values such as {@code oracle} and {@code ORACLE}.
	 */
	private static void applyCaseSensitiveCollation(DbColumn column, DatasourceType source, DatasourceType target, CrossDbReport report) {
		if (target != DatasourceType.SQLSERVER || (source != DatasourceType.POSTGRES && source != DatasourceType.ORACLE)) {
			return;
		}
		String type = column.getDataType() == null ? "" : column.getDataType().trim().toLowerCase(Locale.ROOT);
		if (!type.equals("char") && !type.equals("nchar") && !type.equals("varchar") && !type.equals("nvarchar")) {
			return;
		}
		column.setCollation("Latin1_General_100_BIN2");
		report.warnOnce("sqlserver-case-sensitive",
			"character columns were written with Latin1_General_100_BIN2 so values that differ only by case stay distinct");
	}

	private static void dropPartialPrimaryKey(DbTable table, CrossDbReport report) {
		if (table.getPkColumnNames() == null || table.getPkColumnNames().isEmpty()) {
			return;
		}
		boolean missing = false;
		for (String name : table.getPkColumnNames()) {
			if (table.findColumn(name).isEmpty()) {
				missing = true;
				break;
			}
		}
		if (missing) {
			report.warn("dropped primary key on " + table.qualifiedName() + " because a key column was omitted");
			table.getPkColumnNames().clear();
			table.setPkConstraintName(null);
		}
	}

	private static void dropBrokenKeys(DbModel model, CrossDbReport report) {
		if (model.getContraintInfos() != null) {
			Iterator<ContraintInfo> it = model.getContraintInfos().iterator();
			while (it.hasNext()) {
				ContraintInfo info = it.next();
				if (!columnsPresent(model, info.getSchema(), info.getTableName(), info.getColumnNames())) {
					report.warn("dropped unique constraint " + info.getConstraintName() + " because a column was omitted");
					it.remove();
				}
			}
		}
		if (model.getReferenceInfos() != null) {
			Iterator<ReferenceInfo> it = model.getReferenceInfos().iterator();
			while (it.hasNext()) {
				ReferenceInfo info = it.next();
				boolean src = columnsPresent(model, info.getSrcSchema(), info.getSrcTableName(), info.getSrcColumnNames());
				boolean ref = columnsPresent(model, info.getRefSchema(), info.getRefTableName(), info.getRefColumnNames());
				if (!src || !ref) {
					report.warn("dropped foreign key " + info.getConstraintName() + " because a column was omitted");
					it.remove();
				}
			}
		}
	}

	private static boolean columnsPresent(DbModel model, String schema, String tableName, Iterable<String> columns) {
		if (columns == null) {
			return false;
		}
		DbTable table = model.findTable(qualified(schema, tableName)).orElse(null);
		if (table == null) {
			return false;
		}
		for (String column : columns) {
			if (table.findColumn(column).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static void rewriteIndexes(DbModel model, TargetTypeWriter writer, CrossDbReport report) {
		if (model.getIndexes() == null) {
			return;
		}
		Map<String, Map<String, IndexInfo>> rewritten = new LinkedHashMap<>();
		for (Map<String, IndexInfo> byName : model.getIndexes().values()) {
			if (byName == null) {
				continue;
			}
			for (IndexInfo index : byName.values()) {
				if (index == null) {
					continue;
				}
				if (index.getExtraColumns() != null && !index.getExtraColumns().isEmpty()) {
					report.warn("dropped index " + index.getIndexName() + ": included columns are not portable");
					continue;
				}
				if (index.isXmlIndex() || !isBtree(index.getIndexType())) {
					report.warn("dropped index " + index.getIndexName() + ": " + index.getIndexType());
					continue;
				}
				if (!columnsPresent(model, index.getSchemaName(), index.getTableName(), index.getColumns())) {
					report.warn("dropped index " + index.getIndexName() + " because a column was omitted");
					continue;
				}
				if (index.getColumnPrefixLengths() != null && index.getColumnPrefixLengths().stream().anyMatch(len -> len != null && len > 0)) {
					report.warnOnce("index-prefix", "index prefixes were dropped");
					index.getColumnPrefixLengths().clear();
				}
				index.setIndexType(writer.btreeIndexType());
				rewritten.computeIfAbsent(index.qualifiedTableName(), key -> new LinkedHashMap<>()).put(index.getIndexName(), index);
			}
		}
		model.setIndexes(rewritten);
	}

	/**
	 * Postgres, Oracle, and MySQL treat nulls as distinct in a unique index. SQL Server allows only
	 * one null key, so nullable unique keys are created as filtered unique indexes.
	 */
	private static void filterNullDistinctUniqueKeys(DbModel model, DatasourceType target, CrossDbReport report) {
		if (target != DatasourceType.SQLSERVER) {
			return;
		}
		boolean filtered = false;
		if (model.getIndexes() != null) {
			for (Map<String, IndexInfo> byName : model.getIndexes().values()) {
				if (byName == null) {
					continue;
				}
				for (IndexInfo index : byName.values()) {
					if (index == null || !index.isUnique()) {
						continue;
					}
					String filter = nullDistinctFilter(model, index.getSchemaName(), index.getTableName(), index.getColumns());
					if (filter != null) {
						index.setFilter(filter);
						index.setIndexType("NONCLUSTERED");
						filtered = true;
					}
				}
			}
		}
		if (model.getContraintInfos() != null) {
			if (model.getIndexes() == null) {
				model.setIndexes(new LinkedHashMap<>());
			}
			Iterator<ContraintInfo> it = model.getContraintInfos().iterator();
			while (it.hasNext()) {
				ContraintInfo info = it.next();
				String filter = nullDistinctFilter(model, info.getSchema(), info.getTableName(), info.getColumnNames());
				if (filter == null) {
					continue;
				}
				Map<String, IndexInfo> byName = model.getIndexes()
					.computeIfAbsent(qualified(info.getSchema(), info.getTableName()), key -> new LinkedHashMap<>());
				IndexInfo index = byName.get(info.getConstraintName());
				if (index == null) {
					index = new IndexInfo(info.getSchema(), info.getTableName(), info.getConstraintName(), true, "NONCLUSTERED");
					if (info.getColumnNames() != null) {
						index.getColumns().addAll(info.getColumnNames());
					}
					byName.put(index.getIndexName(), index);
				}
				index.setUnique(true);
				index.setIndexType("NONCLUSTERED");
				index.setFilter(filter);
				it.remove();
				filtered = true;
			}
		}
		if (filtered) {
			report.warnOnce("sqlserver-null-distinct-unique",
				"unique indexes on nullable columns were filtered with IS NOT NULL so multiple null keys are allowed");
		}
	}

	private static String nullDistinctFilter(DbModel model, String schema, String tableName, List<String> columns) {
		if (columns == null || columns.isEmpty()) {
			return null;
		}
		DbTable table = model.findTable(qualified(schema, tableName)).orElse(null);
		if (table == null) {
			return null;
		}
		List<String> terms = new ArrayList<>();
		for (String name : columns) {
			DbColumn column = table.findColumn(name).orElse(null);
			if (column != null && column.isNullable()) {
				terms.add(CommonHelpers.bracketQuoted(name) + " IS NOT NULL");
			}
		}
		if (terms.isEmpty()) {
			return null;
		}
		return String.join(" AND ", terms);
	}

	private static boolean isBtree(String indexType) {
		if (indexType == null || indexType.isBlank()) {
			return true;
		}
		String type = indexType.trim().toUpperCase(Locale.ROOT);
		return type.equals("BTREE") || type.equals("CLUSTERED") || type.equals("NONCLUSTERED") || type.equals("NORMAL");
	}

	private static void clearEngineObjects(DbModel model, CrossDbReport report) {
		warnNames(report, "view", model.getViews() == null ? List.of() : model.getViews().stream().map(DbView::qualifiedName).toList());
		warnNames(report, "routine", model.getFunctions() == null ? List.of() : model.getFunctions().stream().map(DbFunction::qualifiedName).toList());
		warnNames(report, "trigger", model.getTriggers() == null ? List.of() : model.getTriggers().stream().map(DbTrigger::getName).toList());
		warnNames(report, "check constraint", model.getCheckConstraints() == null ? List.of()
			: model.getCheckConstraints().stream().map(CheckConstraint::getConstraintName).toList());
		if (model.getSequences() != null) {
			for (var sequence : model.getSequences()) {
				report.warn("omitted sequence " + sequence.qualifiedName());
			}
		}
		if (model.getFullTextIndexes() != null) {
			for (var index : model.getFullTextIndexes()) {
				report.warn("omitted full-text index " + index.qualifiedTableName());
			}
		}
		if (model.getFullTextCatalogs() != null) {
			for (var catalog : model.getFullTextCatalogs()) {
				report.warn("omitted full-text catalog " + catalog.getName());
			}
		}
		if (model.getXmlSchemaCollections() != null) {
			for (var collection : model.getXmlSchemaCollections()) {
				report.warn("omitted xml schema collection " + collection.getName());
			}
		}
		model.setViews(new java.util.LinkedList<>());
		model.setFunctions(new java.util.LinkedList<>());
		model.setTriggers(new java.util.LinkedList<>());
		model.setCheckConstraints(new java.util.LinkedList<>());
		model.setSequences(new java.util.LinkedList<>());
		model.setEnumTypes(new java.util.LinkedList<>());
		model.setDomainTypes(new java.util.LinkedList<>());
		model.setFullTextIndexes(new java.util.LinkedList<>());
		model.setFullTextCatalogs(new java.util.LinkedList<>());
		model.setXmlSchemaCollections(new java.util.LinkedList<>());
	}

	private static void warnNames(CrossDbReport report, String kind, List<String> names) {
		for (String name : names) {
			report.warn("omitted " + kind + " " + name);
		}
	}

	private static void removeTableObjects(DbModel model, String qualifiedName) {
		if (model.getIndexes() != null) {
			model.getIndexes().remove(qualifiedName);
		}
	}

	private static void layoutSchemas(DbModel model, DatasourceType source, DatasourceType target, CrossDbReport report) {
		if (target == DatasourceType.MYSQL) {
			flattenSchemas(model);
			return;
		}
		if (source == DatasourceType.MYSQL) {
			String schema = switch (target) {
				case POSTGRES -> "public";
				case SQLSERVER -> "dbo";
				default -> null;
			};
			if (schema != null) {
				assignSchema(model, schema);
			}
		}
		if (target == DatasourceType.SQLSERVER) {
			renameSchema(model, "public", "dbo", report);
		}
	}

	/**
	 * SQL Server has a built-in database role named {@code public}. That name is not a schema, so it
	 * does not appear in {@code INFORMATION_SCHEMA.SCHEMATA}, and {@code CREATE SCHEMA [public]} fails.
	 */
	private static void renameSchema(DbModel model, String from, String to, CrossDbReport report) {
		boolean renamed = false;
		if (model.getTables() != null) {
			for (DbTable table : model.getTables().values()) {
				if (sameSchema(table.getSchema(), from)) {
					table.setSchema(to);
					renamed = true;
				}
			}
		}
		if (model.getReferenceInfos() != null) {
			for (ReferenceInfo info : model.getReferenceInfos()) {
				if (sameSchema(info.getSrcSchema(), from)) {
					info.setSrcSchema(to);
					renamed = true;
				}
				if (sameSchema(info.getRefSchema(), from)) {
					info.setRefSchema(to);
					renamed = true;
				}
			}
		}
		if (model.getContraintInfos() != null) {
			for (ContraintInfo info : model.getContraintInfos()) {
				if (sameSchema(info.getSchema(), from)) {
					info.setSchema(to);
					renamed = true;
				}
			}
		}
		if (model.getIndexes() != null) {
			for (Map<String, IndexInfo> byName : model.getIndexes().values()) {
				if (byName == null) {
					continue;
				}
				for (IndexInfo index : byName.values()) {
					if (index != null && sameSchema(index.getSchemaName(), from)) {
						index.setSchemaName(to);
						renamed = true;
					}
				}
			}
		}
		if (model.getSchemas() != null) {
			Set<String> schemas = new LinkedHashSet<>();
			for (String schema : model.getSchemas()) {
				if (sameSchema(schema, from)) {
					schemas.add(to);
					renamed = true;
				} else if (schema != null && !schema.isBlank()) {
					schemas.add(schema);
				}
			}
			model.setSchemas(schemas);
		}
		if (!renamed) {
			return;
		}
		rekeyTables(model);
		rekeyIndexes(model);
		report.warnOnce("sqlserver-public-role",
			"SQL Server reserves public for the built-in database role, so schema public was written as dbo");
	}

	private static boolean sameSchema(String schema, String name) {
		return schema != null && schema.equalsIgnoreCase(name);
	}

	private static void flattenSchemas(DbModel model) {
		for (DbTable table : model.getTables().values()) {
			table.setSchema(null);
		}
		clearSchema(model, null);
		rekeyTables(model);
		rekeyIndexes(model);
		model.setSchemas(new LinkedHashSet<>());
	}

	private static void assignSchema(DbModel model, String schema) {
		for (DbTable table : model.getTables().values()) {
			if (table.getSchema() == null || table.getSchema().isBlank()) {
				table.setSchema(schema);
			}
		}
		clearSchema(model, schema);
		rekeyTables(model);
		rekeyIndexes(model);
		Set<String> schemas = new LinkedHashSet<>();
		for (DbTable table : model.getTables().values()) {
			if (table.getSchema() != null && !table.getSchema().isBlank()) {
				schemas.add(table.getSchema());
			}
		}
		model.setSchemas(schemas);
	}

	/** When {@code schema} is null, every schema field is cleared. Otherwise only blank schemas are filled. */
	private static void clearSchema(DbModel model, String schema) {
		boolean fillBlank = schema != null;
		if (model.getReferenceInfos() != null) {
			for (ReferenceInfo info : model.getReferenceInfos()) {
				info.setSrcSchema(adjust(info.getSrcSchema(), schema, fillBlank));
				info.setRefSchema(adjust(info.getRefSchema(), schema, fillBlank));
			}
		}
		if (model.getContraintInfos() != null) {
			for (ContraintInfo info : model.getContraintInfos()) {
				info.setSchema(adjust(info.getSchema(), schema, fillBlank));
			}
		}
		if (model.getCheckConstraints() != null) {
			for (CheckConstraint check : model.getCheckConstraints()) {
				check.setSchema(adjust(check.getSchema(), schema, fillBlank));
			}
		}
		if (model.getIndexes() != null) {
			for (Map<String, IndexInfo> byName : model.getIndexes().values()) {
				if (byName == null) {
					continue;
				}
				for (IndexInfo index : byName.values()) {
					if (index != null) {
						index.setSchemaName(adjust(index.getSchemaName(), schema, fillBlank));
					}
				}
			}
		}
	}

	private static String adjust(String current, String schema, boolean fillBlank) {
		if (!fillBlank) {
			return null;
		}
		if (current == null || current.isBlank()) {
			return schema;
		}
		return current;
	}

	private static void rekeyTables(DbModel model) {
		Map<String, DbTable> updated = new LinkedHashMap<>();
		for (DbTable table : model.getTables().values()) {
			String key = table.qualifiedName();
			if (updated.containsKey(key)) {
				throw Exceptions.badRequest("cross-db-table-name-collision").withExtra("table", key).get();
			}
			updated.put(key, table);
		}
		model.setTables(updated);
	}

	private static void rekeyIndexes(DbModel model) {
		if (model.getIndexes() == null) {
			return;
		}
		Map<String, Map<String, IndexInfo>> updated = new LinkedHashMap<>();
		for (Map<String, IndexInfo> byName : model.getIndexes().values()) {
			if (byName == null) {
				continue;
			}
			for (IndexInfo index : byName.values()) {
				if (index == null) {
					continue;
				}
				updated.computeIfAbsent(index.qualifiedTableName(), key -> new LinkedHashMap<>()).put(index.getIndexName(), index);
			}
		}
		model.setIndexes(updated);
	}

	private static String qualified(String schema, String name) {
		if (schema == null || schema.isBlank()) {
			return name;
		}
		return schema + "." + name;
	}
}
