package com.quemsi.model.flow.db.postgres;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbDomainType;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.util.CommonHelpers;

import lombok.extern.slf4j.Slf4j;

/**
 * Ensures PostgreSQL ENUM types exist on the DbModel before CREATE TABLE.
 * Enum definitions come from {@link DbModel#getEnumTypes()} (written at backup from catalogs).
 * Missing enums may still pick up a label from column defaults; otherwise restore fails fast
 * (page data is not scanned).
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

	public static void ensureEnumTypes(DbModel dbModel) {
		if (dbModel.getEnumTypes() == null) {
			dbModel.setEnumTypes(new ArrayList<>());
		}
		Set<String> known = new LinkedHashSet<>();
		for (DbEnumType existing : dbModel.getEnumTypes()) {
			known.add(existing.qualifiedName());
		}
		Set<String> domainNames = new LinkedHashSet<>();
		if (dbModel.getDomainTypes() != null) {
			for (DbDomainType domain : dbModel.getDomainTypes()) {
				domainNames.add(domain.getName());
				domainNames.add(domain.qualifiedName());
			}
		}

		Map<String, EnumCandidate> missing = new LinkedHashMap<>();
		for (DbTable table : dbModel.getTables().values()) {
			DbColumn[] columns = table.orderedColumns();
			for (DbColumn column : columns) {
				String typeName = baseUdtName(column.getColumnType());
				if (typeName == null || isBuiltin(typeName) || domainNames.contains(typeName)) {
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
			}
		}

		if (missing.isEmpty()) {
			return;
		}

		List<String> unresolved = new ArrayList<>();
		for (EnumCandidate candidate : missing.values()) {
			if (candidate.labels.isEmpty()) {
				unresolved.add(candidate.qualifiedName());
				continue;
			}
			DbEnumType enumType = DbEnumType.builder()
				.schema(candidate.schema)
				.name(candidate.name)
				.labels(new ArrayList<>(candidate.labels))
				.build();
			dbModel.getEnumTypes().add(enumType);
			known.add(enumType.qualifiedName());
			log.info("Reconstructed enum {} with labels {} from column defaults",
				enumType.qualifiedName(), enumType.getLabels());
		}
		if (!unresolved.isEmpty()) {
			throw Exceptions.badRequest("postgres-enum-types-missing")
				.withExtra("enums", unresolved)
				.withExtra("hint", "Backup db-model.json must include enumTypes from the source catalog")
				.get();
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
}
