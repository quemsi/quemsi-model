package com.quemsi.model.flow.db.cross;

import com.quemsi.model.dto.DatasourceType;

final class DefaultLiterals {
	private DefaultLiterals() {
	}

	static String format(PortableColumn portable, DatasourceType target) {
		LiteralValue literal = portable.getLiteralDefault();
		if (literal == null) {
			return null;
		}
		if (portable.getType() == PortableType.BOOLEAN || literal.kind() == LiteralValue.Kind.BOOLEAN) {
			boolean flag = asBoolean(literal);
			if (target == DatasourceType.POSTGRES) {
				return flag ? "true" : "false";
			}
			return flag ? "1" : "0";
		}
		if (literal.kind() == LiteralValue.Kind.NUMBER) {
			return literal.value();
		}
		String text = literal.value() == null ? "" : literal.value();
		if (target == DatasourceType.MYSQL) {
			return text;
		}
		return "'" + text.replace("'", "''") + "'";
	}

	private static boolean asBoolean(LiteralValue literal) {
		return switch (literal.kind()) {
			case BOOLEAN -> Boolean.parseBoolean(literal.value());
			case NUMBER -> {
				try {
					yield Double.parseDouble(literal.value()) != 0d;
				} catch (NumberFormatException ex) {
					yield false;
				}
			}
			case STRING -> "true".equalsIgnoreCase(literal.value()) || "1".equals(literal.value());
		};
	}
}
