package com.quemsi.model.flow.db.cross;

import com.quemsi.model.flow.db.sql.DbColumn;

final class PortableColumns {
	private PortableColumns() {
	}

	static PortableColumn of(DbColumn column, PortableType type, Integer length, Integer precision, Integer scale) {
		return of(column, type, length, precision, scale, column.isIdentity());
	}

	static PortableColumn of(DbColumn column, PortableType type, Integer length, Integer precision, Integer scale,
			boolean identity) {
		return PortableColumn.builder()
			.type(type)
			.length(length)
			.precision(precision)
			.scale(scale)
			.nullable(column.isNullable())
			.identity(identity)
			.literalDefault(literal(column))
			.build();
	}

	static LiteralValue literal(DbColumn column) {
		if (column.isIdentity() && IdentityFlags.isNextvalDefault(column.getColumnDefault())) {
			return null;
		}
		return LiteralDefaults.parse(column.getColumnDefault()).orElse(null);
	}

	static boolean expressionDefault(DbColumn column) {
		if (column.getColumnDefault() == null || column.getColumnDefault().isBlank()) {
			return false;
		}
		return literal(column) == null;
	}
}
