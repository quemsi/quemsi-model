package com.quemsi.model.flow.db.cross;

/**
 * A column default that is a number, a quoted string, or a boolean,
 * after dialect wrappers have been stripped.
 */
public record LiteralValue(Kind kind, String value) {
	public enum Kind {
		NUMBER,
		STRING,
		BOOLEAN
	}

	public static LiteralValue number(String value) {
		return new LiteralValue(Kind.NUMBER, value);
	}

	public static LiteralValue string(String value) {
		return new LiteralValue(Kind.STRING, value);
	}

	public static LiteralValue bool(boolean value) {
		return new LiteralValue(Kind.BOOLEAN, Boolean.toString(value));
	}
}
