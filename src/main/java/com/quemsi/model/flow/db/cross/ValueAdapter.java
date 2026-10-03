package com.quemsi.model.flow.db.cross;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.quemsi.commons.util.Exceptions;
import com.quemsi.model.dto.DatasourceType;
import com.quemsi.model.flow.db.sql.DbColumn;
import com.quemsi.model.flow.db.sql.DbTable;
import com.quemsi.model.flow.in.CustomSerializedColumn;
import com.quemsi.model.flow.in.TableData.DataPage;

/**
 * Projects archive rows onto the rewritten column list and converts cells
 * into values the target binder already accepts.
 */
public final class ValueAdapter {
	private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	private static final long SECONDS_THRESHOLD = 100_000_000_000L;

	private ValueAdapter() {
	}

	public static DataPage project(DbTable table, DataPage page, CrossDbPlan plan) {
		int[] kept = plan.sourceIndexes(table.qualifiedName());
		if (kept == null || page == null || page.getData() == null) {
			return page;
		}
		DbColumn[] columns = table.orderedColumns();
		Map<Object, Object[]> projected = new LinkedHashMap<>();
		for (Map.Entry<Object, Object[]> row : page.getData().entrySet()) {
			Object[] source = row.getValue() == null ? new Object[0] : row.getValue();
			Object[] target = new Object[kept.length];
			for (int i = 0; i < kept.length; i++) {
				int sourceIndex = kept[i];
				Object value = sourceIndex < source.length ? source[sourceIndex] : null;
				target[i] = convert(table, columns[i], value, plan.target());
			}
			projected.put(row.getKey(), target);
		}
		return new DataPage(page.getPageNum(), projected);
	}

	static Object convert(DbTable table, DbColumn column, Object value, DatasourceType target) {
		if (value == null || column.getPortable() == null) {
			return value;
		}
		try {
			return convert(column.getPortable().getType(), value, target);
		} catch (RuntimeException ex) {
			throw Exceptions.server("unable-to-convert-cross-db-value")
				.withExtra("table", table.qualifiedName())
				.withExtra("column", column.getName())
				.withCause(ex)
				.get();
		}
	}

	static Object convert(PortableType type, Object value, DatasourceType target) {
		return switch (type) {
			case BOOLEAN -> booleanValue(value, target);
			case SMALLINT, INTEGER -> integral(value, false);
			case BIGINT -> integral(value, true);
			case NUMERIC -> numeric(value);
			case FLOAT, REAL -> floating(value);
			case CHAR, VARCHAR, TEXT -> text(value);
			case BINARY -> binary(value);
			case UUID -> uuid(value);
			case DATE -> temporal(value, type, target);
			case TIME -> temporal(value, type, target);
			case TIMESTAMP, TIMESTAMPTZ -> temporal(value, type, target);
		};
	}

	private static Object booleanValue(Object value, DatasourceType target) {
		boolean flag = asBoolean(value);
		if (target == DatasourceType.ORACLE) {
			return flag ? 1 : 0;
		}
		return flag;
	}

	private static boolean asBoolean(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.doubleValue() != 0d;
		}
		String text = value.toString().trim();
		return "1".equals(text) || "true".equalsIgnoreCase(text) || "t".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
	}

	private static Object integral(Object value, boolean wide) {
		if (value instanceof Boolean bool) {
			return bool ? 1 : 0;
		}
		if (value instanceof String str) {
			if (str.isBlank()) {
				return null;
			}
			return wide ? Long.valueOf(str.trim()) : Integer.valueOf(new BigDecimal(str.trim()).intValue());
		}
		if (value instanceof Number number) {
			if (wide) {
				return number.longValue();
			}
			return number.intValue();
		}
		return value;
	}

	private static Object numeric(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number || value instanceof String) {
			String text = value.toString().trim();
			if (text.isEmpty()) {
				return null;
			}
			return new BigDecimal(text);
		}
		return value;
	}

	private static Object floating(Object value) {
		if (value instanceof Double || value instanceof Float) {
			return value;
		}
		if (value instanceof Number number) {
			return number.doubleValue();
		}
		if (value instanceof String str) {
			return str.isBlank() ? null : Double.valueOf(str.trim());
		}
		return value;
	}

	private static Object text(Object value) {
		if (value instanceof String str) {
			return str;
		}
		if (value instanceof byte[] bytes) {
			return new String(bytes);
		}
		return String.valueOf(value);
	}

	private static Object binary(Object value) {
		if (value instanceof byte[] bytes) {
			return bytes;
		}
		if (value instanceof CustomSerializedColumn serialized) {
			return serialized.getData();
		}
		if (value instanceof Map<?, ?> map) {
			Object data = map.containsKey("data") ? map.get("data") : map.get("value");
			return data == null ? null : binary(data);
		}
		if (value instanceof String str) {
			String trimmed = str.trim();
			if (trimmed.isEmpty()) {
				return null;
			}
			return Base64.getDecoder().decode(trimmed);
		}
		if (value instanceof List<?> list) {
			byte[] bytes = new byte[list.size()];
			for (int i = 0; i < list.size(); i++) {
				Object item = list.get(i);
				bytes[i] = item instanceof Number number ? number.byteValue() : Byte.parseByte(item.toString());
			}
			return bytes;
		}
		throw new IllegalArgumentException("unsupported binary value");
	}

	private static Object uuid(Object value) {
		if (value instanceof UUID id) {
			return id.toString();
		}
		String text = value.toString().trim();
		return UUID.fromString(text).toString().toLowerCase(Locale.ROOT);
	}

	private static Object temporal(Object value, PortableType type, DatasourceType target) {
		if (value instanceof String str) {
			return str;
		}
		if (value instanceof Number number) {
			return formatInstant(toInstant(number.longValue()), type, target);
		}
		if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number) {
			int year = component(list, 0, 1970);
			int month = component(list, 1, 1);
			int day = component(list, 2, 1);
			int hour = component(list, 3, 0);
			int minute = component(list, 4, 0);
			int second = component(list, 5, 0);
			LocalDateTime dateTime = LocalDateTime.of(year, month, day, hour, minute, second);
			return formatDateTime(dateTime, type, target);
		}
		return value;
	}

	private static Instant toInstant(long raw) {
		if (Math.abs(raw) < SECONDS_THRESHOLD) {
			return Instant.ofEpochSecond(raw);
		}
		return Instant.ofEpochMilli(raw);
	}

	private static String formatInstant(Instant instant, PortableType type, DatasourceType target) {
		return formatDateTime(LocalDateTime.ofInstant(instant, ZoneOffset.UTC), type, target);
	}

	private static String formatDateTime(LocalDateTime dateTime, PortableType type, DatasourceType target) {
		return switch (type) {
			case DATE -> dateTime.toLocalDate().toString();
			case TIME -> dateTime.toLocalTime().toString();
			case TIMESTAMPTZ -> target == DatasourceType.POSTGRES
				? dateTime.format(LOCAL_DATE_TIME) + "Z"
				: dateTime.format(LOCAL_DATE_TIME);
			default -> dateTime.format(LOCAL_DATE_TIME);
		};
	}

	private static int component(List<?> list, int index, int fallback) {
		if (index >= list.size() || !(list.get(index) instanceof Number number)) {
			return fallback;
		}
		return number.intValue();
	}
}
