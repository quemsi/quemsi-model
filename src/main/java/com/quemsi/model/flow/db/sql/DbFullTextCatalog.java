package com.quemsi.model.flow.db.sql;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbFullTextCatalog {
	private String name;
	private boolean isDefault;
}
