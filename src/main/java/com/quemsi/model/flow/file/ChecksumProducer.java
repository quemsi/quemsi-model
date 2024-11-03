package com.quemsi.model.flow.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

public abstract class ChecksumProducer {
	public abstract boolean isDifferent(File file1, File file2) throws IOException;
	public abstract boolean isDifferent(File file1, String file2Checksum) throws IOException;
	public abstract String getFileChecksum(File file) throws IOException;
	public String getFileChecksum(MessageDigest digest, File file) throws IOException {
		try (FileInputStream fis = new FileInputStream(file)) {

			byte[] byteArray = new byte[1024];
			int bytesCount = 0;

			while ((bytesCount = fis.read(byteArray)) != -1) {
				digest.update(byteArray, 0, bytesCount);
			}

			byte[] bytes = digest.digest();

			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < bytes.length; i++) {
				sb.append(Integer.toString((bytes[i] & 0xff) + 0x100, 16).substring(1));
			}

			return sb.toString();
		}
	}
}
