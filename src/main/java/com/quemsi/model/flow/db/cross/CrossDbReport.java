package com.quemsi.model.flow.db.cross;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CrossDbReport {
	private final List<String> warnings = new ArrayList<>();
	private final Set<String> onceKeys = new HashSet<>();

	public void warn(String message) {
		warnings.add(message);
	}

	public void warnOnce(String key, String message) {
		if (onceKeys.add(key)) {
			warnings.add(message);
		}
	}

	public List<String> warnings() {
		return List.copyOf(warnings);
	}

	public int size() {
		return warnings.size();
	}
}
