package com.quemsi.model.flow.db.sqlserver;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.quemsi.commons.util.BaseRuntimeException;

public class DatasourceFactorySqlserverRequireDefinitionTest {

	@Test
	public void givenBlankDefinition_whenRequireDefinition_thenFailBackup() {
		BaseRuntimeException ex = assertThrows(BaseRuntimeException.class,
				() -> DatasourceFactorySqlserver.requireDefinition("check-constraint", "dbo", "authors", "CK_au_id", null));
		assertThat(ex.getMessageId(), equalTo("view-definition-permission-required"));
		assertThat(ex.getExtra().get("requiredPermission"), equalTo("VIEW DEFINITION"));
		assertThat(ex.getExtra().get("objectType"), equalTo("check-constraint"));
		assertThat(ex.getExtra().get("objectName"), equalTo("CK_au_id"));
		assertThat(ex.getExtra().get("tableName"), equalTo("authors"));
	}

	@Test
	public void givenDefinition_whenRequireDefinition_thenOk() {
		DatasourceFactorySqlserver.requireDefinition("view", "dbo", null, "titleview", "SELECT 1");
	}
}
