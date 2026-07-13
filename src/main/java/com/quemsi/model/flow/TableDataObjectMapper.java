package com.quemsi.model.flow;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.quemsi.commons.util.ApacheDurationDeserializer;
import com.quemsi.commons.util.ApacheDurationSerializer;

public final class TableDataObjectMapper {
	private static final String DATA_DATE_FORMAT = "yyyy-MM-dd";
	private static final String DATA_DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS";

	private TableDataObjectMapper() {
	}

	public static ObjectMapper create() {
		ObjectMapper dataMapper = new ObjectMapper();
		JavaTimeModule module = new JavaTimeModule();
		module.addSerializer(new LocalDateSerializer(DateTimeFormatter.ofPattern(DATA_DATE_FORMAT)));
		module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATA_DATE_FORMAT)));
		module.addSerializer(new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATA_DATE_TIME_FORMAT)));
		module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATA_DATE_TIME_FORMAT)));
		module.addSerializer(new ApacheDurationSerializer());
		module.addDeserializer(Duration.class, new ApacheDurationDeserializer());
		dataMapper.registerModule(module);
		dataMapper.setSerializationInclusion(Include.NON_NULL);
		dataMapper.enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
		dataMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		dataMapper.enable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
		dataMapper.setDateFormat(new SimpleDateFormat(DATA_DATE_TIME_FORMAT));
		return dataMapper;
	}
}
