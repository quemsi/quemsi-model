package com.quemsi.model.flow.db.cross;

import java.util.Locale;
import java.util.Set;

import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbDomainType;
import com.quemsi.model.flow.db.sql.DbEnumType;
import com.quemsi.model.flow.db.sql.DbModel;
import com.quemsi.model.util.CommonHelpers;

public final class PostgresTypeReader implements SourceTypeReader {
	private static final Set<String> OMITTED = Set.of(
		"tsvector", "tsquery", "jsonpath", "point", "line", "lseg", "box", "path", "polygon", "circle",
		"interval", "bit", "varbit", "int4range", "int8range", "numrange", "tsrange", "tstzrange", "daterange"
	);

	@Override
	public DatasourceType source() {
		return DatasourceType.POSTGRES;
	}

	@Override
	public ColumnMapping read(DbColumn column, DbModel model, CrossDbReport report) {
		String raw = column.getDataType() != null ? column.getDataType() : column.getColumnType();
		if (raw != null && (raw.trim().endsWith("[]") || isArrayUdt(raw))) {
			return ColumnMapping.omit("array");
		}
		String type = TypeNames.base(raw);
		if (OMITTED.contains(type) || type.endsWith("range")) {
			return ColumnMapping.omit(type);
		}
		DbEnumType enumType = findEnum(model, column, type);
		if (enumType != null) {
			int length = enumLength(enumType);
			report.warnOnce("postgres-enum", "Postgres enum constraints were dropped and stored as varchar");
			return ColumnMapping.mapped(PortableColumns.of(column, PortableType.VARCHAR, length, null, null));
		}
		DbDomainType domain = findDomain(model, column, type);
		if (domain != null) {
			return readDomain(column, domain, report);
		}
		return mapKnown(column, type, report);
	}

	private ColumnMapping readDomain(DbColumn column, DbDomainType domain, CrossDbReport report) {
		String base = domain.getBaseType() == null ? "" : domain.getBaseType();
		String type = TypeNames.base(base);
		Integer length = TypeNames.lengthInParens(base);
		if (length == null) {
			length = column.getMaxLength();
		}
		ColumnMapping mapped = mapKnown(copyLengths(column, length), type, report);
		if (mapped.omit()) {
			return ColumnMapping.omit("domain " + domain.getName());
		}
		report.warnOnce("postgres-domain", "Postgres domain constraints were dropped");
		return mapped;
	}

	private static DbColumn copyLengths(DbColumn column, Integer length) {
		if (length == null || length.equals(column.getMaxLength())) {
			return column;
		}
		DbColumn copy = DbColumn.builder()
			.name(column.getName())
			.dataType(column.getDataType())
			.columnType(column.getColumnType())
			.maxLength(length)
			.numPrecision(column.getNumPrecision())
			.numScale(column.getNumScale())
			.columnDefault(column.getColumnDefault())
			.nullable(column.isNullable())
			.identity(column.isIdentity())
			.build();
		return copy;
	}

	private ColumnMapping mapKnown(DbColumn column, String type, CrossDbReport report) {
		PortableType portable = switch (type) {
			case "bool", "boolean" -> PortableType.BOOLEAN;
			case "int2", "smallint", "smallserial" -> PortableType.SMALLINT;
			case "int4", "integer", "int", "serial" -> PortableType.INTEGER;
			case "int8", "bigint", "bigserial" -> PortableType.BIGINT;
			case "numeric", "decimal", "money" -> PortableType.NUMERIC;
			case "float4", "real" -> PortableType.REAL;
			case "float8", "double precision" -> PortableType.FLOAT;
			case "bpchar", "char", "character" -> PortableType.CHAR;
			case "varchar", "character varying" -> PortableType.VARCHAR;
			case "text", "json", "jsonb", "xml", "name", "inet", "cidr", "macaddr", "macaddr8" -> PortableType.TEXT;
			case "bytea" -> PortableType.BINARY;
			case "uuid" -> PortableType.UUID;
			case "date" -> PortableType.DATE;
			case "time", "timetz" -> PortableType.TIME;
			case "timestamp" -> PortableType.TIMESTAMP;
			case "timestamptz" -> PortableType.TIMESTAMPTZ;
			case "oid" -> PortableType.BIGINT;
			default -> null;
		};
		if (portable == null) {
			return ColumnMapping.omit(type.isEmpty() ? "unknown type" : type);
		}
		if ("json".equals(type) || "jsonb".equals(type)) {
			report.warnOnce("postgres-json", "json and jsonb columns were stored as text");
		}
		if ("money".equals(type)) {
			report.warnOnce("postgres-money", "money columns were stored as numeric");
		}
		if ("timetz".equals(type)) {
			report.warnOnce("postgres-timetz", "time with time zone was stored as time");
		}
		boolean identity = column.isIdentity() || type.equals("serial") || type.equals("bigserial") || type.equals("smallserial");
		Integer precision = portable == PortableType.NUMERIC
			? ("money".equals(type) ? 19 : column.getNumPrecision()) : null;
		Integer scale = portable == PortableType.NUMERIC
			? ("money".equals(type) ? 2 : column.getNumScale()) : null;
		Integer length = (portable == PortableType.CHAR || portable == PortableType.VARCHAR) ? column.getMaxLength() : null;
		return ColumnMapping.mapped(PortableColumns.of(column, portable, length, precision, scale, identity));
	}

	private static boolean isArrayUdt(String raw) {
		String trimmed = raw.trim().toLowerCase(Locale.ROOT);
		return trimmed.startsWith("_") && trimmed.length() > 1 && !trimmed.contains(" ");
	}

	private static int enumLength(DbEnumType enumType) {
		int length = 1;
		if (enumType.getLabels() != null) {
			for (String label : enumType.getLabels()) {
				if (label != null) {
					length = Math.max(length, label.length());
				}
			}
		}
		return length;
	}

	private static DbEnumType findEnum(DbModel model, DbColumn column, String type) {
		if (model.getEnumTypes() == null || type.isEmpty()) {
			return null;
		}
		String schema = column.getTable() != null ? column.getTable().getSchema() : null;
		for (DbEnumType enumType : model.getEnumTypes()) {
			if (type.equalsIgnoreCase(enumType.getName())
				|| type.equalsIgnoreCase(enumType.qualifiedName())
				|| type.equalsIgnoreCase(CommonHelpers.qualifiedName(schema, enumType.getName()))) {
				return enumType;
			}
		}
		return null;
	}

	private static DbDomainType findDomain(DbModel model, DbColumn column, String type) {
		if (model.getDomainTypes() == null || type.isEmpty()) {
			return null;
		}
		String schema = column.getTable() != null ? column.getTable().getSchema() : null;
		for (DbDomainType domain : model.getDomainTypes()) {
			if (type.equalsIgnoreCase(domain.getName())
				|| type.equalsIgnoreCase(domain.qualifiedName())
				|| type.equalsIgnoreCase(CommonHelpers.qualifiedName(schema, domain.getName()))) {
				return domain;
			}
		}
		return null;
	}
}
