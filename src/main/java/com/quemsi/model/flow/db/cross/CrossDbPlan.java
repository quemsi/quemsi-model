package com.quemsi.model.flow.db.cross;

import java.util.HashMap;
import java.util.Map;

import com.quemsi.model.dto.DatasourceType;

/**
 * In-memory result of translating a backup model onto another SQL engine.
 * Archive entry names stay on the source qualified table name.
 */
public final class CrossDbPlan {
	private final DatasourceType source;
	private final DatasourceType target;
	private final CrossDbReport report;
	private final Map<String, String> archiveNames = new HashMap<>();
	private final Map<String, int[]> sourceIndexes = new HashMap<>();

	public CrossDbPlan(DatasourceType source, DatasourceType target, CrossDbReport report) {
		this.source = source;
		this.target = target;
		this.report = report;
	}

	public DatasourceType source() {
		return source;
	}

	public DatasourceType target() {
		return target;
	}

	public CrossDbReport report() {
		return report;
	}

	public void putArchiveName(String targetQualifiedName, String archiveQualifiedName) {
		archiveNames.put(targetQualifiedName, archiveQualifiedName);
	}

	public String archiveName(String targetQualifiedName) {
		return archiveNames.getOrDefault(targetQualifiedName, targetQualifiedName);
	}

	public void putSourceIndexes(String targetQualifiedName, int[] indexes) {
		sourceIndexes.put(targetQualifiedName, indexes);
	}

	public int[] sourceIndexes(String targetQualifiedName) {
		return sourceIndexes.get(targetQualifiedName);
	}
}
