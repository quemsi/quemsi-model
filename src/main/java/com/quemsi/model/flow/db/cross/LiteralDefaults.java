package com.quemsi.model.flow.db.cross;

import java.util.Optional;

/**
 * Keeps numeric and quoted-string defaults. Expression defaults such as
 * {@code getdate()} or {@code nextval(...)} are rejected.
 */
public final class LiteralDefaults {
	private LiteralDefaults() {
	}

	public static Optional<LiteralValue> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String value = raw.trim();
		int cast = value.indexOf("::");
		if (cast > 0) {
			value = value.substring(0, cast).trim();
		}
		while (value.length() >= 2 && value.startsWith("(") && value.endsWith(")")) {
			value = value.substring(1, value.length() - 1).trim();
		}
		if (value.length() >= 3 && (value.startsWith("N'") || value.startsWith("n'")) && value.endsWith("'")) {
			value = value.substring(1);
		}
		if (value.equalsIgnoreCase("null")) {
			return Optional.empty();
		}
		if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
			return Optional.of(LiteralValue.bool(value.equalsIgnoreCase("true")));
		}
		if (value.matches("-?\\d+(\\.\\d+)?")) {
			return Optional.of(LiteralValue.number(value));
		}
		if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
			String inner = value.substring(1, value.length() - 1).replace("''", "'");
			return Optional.of(LiteralValue.string(inner));
		}
		return Optional.empty();
	}
}
