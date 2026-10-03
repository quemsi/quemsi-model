package com.quemsi.model.flow.db.cross;

import java.util.Locale;

final class TypeNames {
	private TypeNames() {
	}

	static String base(String type) {
		if (type == null || type.isBlank()) {
			return "";
		}
		String normalized = type.trim().toLowerCase(Locale.ROOT);
		int paren = normalized.indexOf('(');
		if (paren > 0) {
			normalized = normalized.substring(0, paren).trim();
		}
		if (normalized.endsWith("[]")) {
			normalized = normalized.substring(0, normalized.length() - 2).trim();
		}
		return normalized;
	}

	static boolean unsigned(String columnType) {
		return columnType != null && columnType.toLowerCase(Locale.ROOT).contains("unsigned");
	}

	static boolean maxLength(Integer maxLength, String columnType) {
		if (maxLength != null && maxLength < 0) {
			return true;
		}
		return columnType != null && columnType.toLowerCase(Locale.ROOT).contains("(max)");
	}

	static Integer lengthInParens(String type) {
		if (type == null) {
			return null;
		}
		int open = type.indexOf('(');
		int close = type.indexOf(')', open + 1);
		if (open < 0 || close < 0) {
			return null;
		}
		String inner = type.substring(open + 1, close).trim();
		int comma = inner.indexOf(',');
		if (comma >= 0) {
			inner = inner.substring(0, comma).trim();
		}
		try {
			return Integer.valueOf(inner);
		} catch (NumberFormatException ex) {
			return null;
		}
	}
}
