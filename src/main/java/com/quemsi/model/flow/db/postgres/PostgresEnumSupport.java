package com.quemsi.model.flow.db.postgres;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quemsi.model.flow.DataPackage;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.TableData;
import com.quemsi.model.util.CommonHelpers;

import lombok.extern.slf4j.Slf4j;

/**
 * Ensures PostgreSQL ENUM types exist on the DbModel before CREATE TABLE.
 * Newer backups include {@code enumTypes}; older backups are reconstructed from
 * column defaults and distinct values in table data packages.
 */
@Slf4j
public final class PostgresEnumSupport {
	private static final Set<String> BUILTIN_UDT_NAMES = Set.of(
		"int2", "int4", "int8", "float4", "float8", "numeric", "decimal",
		"varchar", "bpchar", "text", "char", "bool", "boolean",
		"date", "time", "timetz", "timestamp", "timestamptz",
		"json", "jsonb", "uuid", "bytea", "money", "xml",
		"inet", "cidr", "macaddr", "macaddr8", "oid", "name",
		"tsvector", "tsquery", "bit", "varbit", "interval", "point", "line",
		"lseg", "box", "path", "polygon", "circle", "txid_snapshot", "pg_lsn"
	);

	private PostgresEnumSupport() {
	}

	public static void ensureEnumTypes(DbModel dbModel, Map<String, DataPackage> namedPackages, ObjectMapper objectMapper) {
		if (dbModel.getEnumTypes() == null) {
			dbModel.setEnumTypes(new ArrayList<>());
		}
		Set<String> known = new LinkedHashSet<>();
		for (DbEnumType existing : dbModel.getEnumTypes()) {
			known.add(existing.qualifiedName());
		}

		Map<String, EnumCandidate> missing = new LinkedHashMap<>();
		for (DbTable table : dbModel.getTables().values()) {
			DbColumn[] columns = table.orderedColumns();
			for (int i = 0; i < columns.length; i++) {
				DbColumn column = columns[i];
				String typeName = baseUdtName(column.getColumnType());
				if (typeName == null || isBuiltin(typeName)) {
					continue;
				}
				String schema = table.getSchema() != null ? table.getSchema() : "public";
				String qualified = CommonHelpers.qualifiedName(schema, typeName);
				if (known.contains(qualified)) {
					continue;
				}
				EnumCandidate candidate = missing.computeIfAbsent(qualified,
					k -> new EnumCandidate(schema, typeName));
				candidate.addLabelFromDefault(column.getColumnDefault());
				candidate.columnRefs.add(new ColumnRef(table.qualifiedName(), i));
			}
		}

		if (missing.isEmpty()) {
			return;
		}

		for (EnumCandidate candidate : missing.values()) {
			for (ColumnRef ref : candidate.columnRefs) {
				collectLabelsFromData(candidate, ref, namedPackages, objectMapper);
			}
			if (candidate.labels.isEmpty()) {
				log.warn("Unable to reconstruct enum {} — no labels found in defaults or data",
					candidate.qualifiedName());
				continue;
			}
			DbEnumType enumType = DbEnumType.builder()
				.schema(candidate.schema)
				.name(candidate.name)
				.labels(new ArrayList<>(candidate.labels))
				.build();
			dbModel.getEnumTypes().add(enumType);
			known.add(enumType.qualifiedName());
			log.info("Reconstructed enum {} with labels {}", enumType.qualifiedName(), enumType.getLabels());
		}
	}

	private static void collectLabelsFromData(
		EnumCandidate candidate,
		ColumnRef ref,
		Map<String, DataPackage> namedPackages,
		ObjectMapper objectMapper
	) {
		if (namedPackages == null || objectMapper == null) {
			return;
		}
		String fileName = CommonHelpers.dataFileName(ref.tableQualifiedName);
		DataPackage dataPackage = namedPackages.get(fileName);
		if (dataPackage == null) {
			return;
		}
		try (InputStream in = dataPackage.getInputStream()) {
			String json = IOUtils.toString(in, StandardCharsets.UTF_8);
			TableData tableData = objectMapper.readValue(json, TableData.class);
			if (tableData.getDataPages() == null) {
				return;
			}
			for (TableData.DataPage page : tableData.getDataPages()) {
				if (page.getData() == null) {
					continue;
				}
				for (Object[] row : page.getData().values()) {
					if (row == null || ref.columnIndex >= row.length) {
						continue;
					}
					Object val = row[ref.columnIndex];
					if (val instanceof String s && !s.isEmpty()) {
						candidate.labels.add(s);
					}
				}
			}
		} catch (Exception e) {
			log.warn("Unable to read data package {} while reconstructing enum {}", fileName, candidate.qualifiedName(), e);
		}
	}

	static String baseUdtName(String columnType) {
		if (columnType == null || columnType.isBlank()) {
			return null;
		}
		String type = columnType.trim();
		int paren = type.indexOf('(');
		if (paren > 0) {
			type = type.substring(0, paren).trim();
		}
		if (type.endsWith("[]")) {
			return null;
		}
		if (type.startsWith("_") && type.length() > 1) {
			return null;
		}
		return type;
	}

	static boolean isBuiltin(String typeName) {
		return BUILTIN_UDT_NAMES.contains(typeName.toLowerCase());
	}

	private static final class EnumCandidate {
		final String schema;
		final String name;
		final LinkedHashSet<String> labels = new LinkedHashSet<>();
		final List<ColumnRef> columnRefs = new ArrayList<>();

		EnumCandidate(String schema, String name) {
			this.schema = schema;
			this.name = name;
		}

		String qualifiedName() {
			return CommonHelpers.qualifiedName(schema, name);
		}

		void addLabelFromDefault(String columnDefault) {
			if (columnDefault == null) {
				return;
			}
			Pattern pattern = Pattern.compile("'((?:[^']|'')*)'::" + Pattern.quote(name) + "\\b");
			Matcher matcher = pattern.matcher(columnDefault);
			if (matcher.find()) {
				labels.add(matcher.group(1).replace("''", "'"));
			}
		}
	}

	private static final class ColumnRef {
		final String tableQualifiedName;
		final int columnIndex;

		ColumnRef(String tableQualifiedName, int columnIndex) {
			this.tableQualifiedName = tableQualifiedName;
			this.columnIndex = columnIndex;
		}
	}
}
