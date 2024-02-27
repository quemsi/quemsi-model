package com.biddflux.model.flow.file;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChecksumProducerMD5 extends ChecksumProducer {

	public MessageDigest md5() {
		try {
			return MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			log.error("error creating md5Digest", e);
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
		return super.getFileChecksum(md5(), file);
	}
}
