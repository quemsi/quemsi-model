package com.quemsi.model.flow.db.cross;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PortableColumn {
	PortableType type;
	Integer length;
	Integer precision;
	Integer scale;
	boolean nullable;
	boolean identity;
	LiteralValue literalDefault;
}
