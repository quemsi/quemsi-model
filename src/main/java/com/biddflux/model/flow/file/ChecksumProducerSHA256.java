package com.biddflux.model.flow.file;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChecksumProducerSHA256 extends ChecksumProducer {
	public MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			log.error("error creating shaDigest", e);
		}
		return null;
	}
	@Override
	public boolean isDifferent(File file1, File file2) throws IOException {
		return !getFileChecksum(file1).equals(getFileChecksum(file2));
	}
	@Override
	public boolean isDifferent(File file1, String file2CheckSum) throws IOException {
		return !getFileChecksum(file1).equals(file2CheckSum);
	}
	@Override
	public String getFileChecksum(File file) throws IOException {
		return super.getFileChecksum(sha256(), file);
	}
}
