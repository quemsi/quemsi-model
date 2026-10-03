package com.quemsi.model.flow.db.cross;

/**
 * Types that can be restored onto MySQL, Postgres, SQL Server, and Oracle
 * without engine-specific operators.
 */
public enum PortableType {
	BOOLEAN,
	SMALLINT,
	INTEGER,
	BIGINT,
	NUMERIC,
	FLOAT,
	REAL,
	CHAR,
	VARCHAR,
	TEXT,
	BINARY,
	UUID,
	DATE,
	TIME,
	TIMESTAMP,
	TIMESTAMPTZ
}
