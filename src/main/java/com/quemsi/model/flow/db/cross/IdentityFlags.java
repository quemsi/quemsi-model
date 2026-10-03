package com.quemsi.model.flow.db.cross;

import java.util.Locale;

import com.quemsi.commons.util.CommonOps;

/** Identity detection shared by datasource model loaders. */
public final class IdentityFlags {
	private IdentityFlags() {
	}

	public static boolean postgres(String isIdentity, String columnDefault) {
		return CommonOps.isTrue(isIdentity) || isNextvalDefault(columnDefault);
	}

	public static boolean mysql(String extra) {
		return extra != null && extra.toLowerCase(Locale.ROOT).contains("auto_increment");
	}

	public static boolean oracle(String identityColumn) {
		return "YES".equalsIgnoreCase(identityColumn);
	}

	public static boolean isNextvalDefault(String columnDefault) {
		return columnDefault != null && columnDefault.toLowerCase(Locale.ROOT).contains("nextval(");
	}
}
