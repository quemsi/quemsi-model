package com.quemsi.model.flow.db.sql;

import com.quemsi.model.util.CommonHelpers;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbXmlSchemaCollection {
	private String schema;
	private String name;
	/** XSD text from XML_SCHEMA_NAMESPACE(...). */
	private String definition;

	public String qualifiedName() {
		return CommonHelpers.qualifiedName(schema, name);
	}
}
