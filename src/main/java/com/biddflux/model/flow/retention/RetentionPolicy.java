package com.biddflux.model.flow.retention;

import com.biddflux.model.flow.out.Storage;

public interface RetentionPolicy {
	String getName();
	void setStorage(Storage storage);
	void clear();
}
