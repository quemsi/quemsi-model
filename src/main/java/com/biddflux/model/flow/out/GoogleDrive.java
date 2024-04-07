package com.biddflux.model.flow.out;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Async;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.commons.util.FileResource;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp.Browser;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.media.MediaHttpDownloader;
import com.google.api.client.googleapis.media.MediaHttpDownloaderProgressListener;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GoogleDrive {
	private static final String CREDENTIAL_STORE_ID = "StoredCredential";
	private static final String APPLICATION_NAME = "Bakerup Google Drive Storage";
	protected static final String FOLDER_MIMETYPE = "application/vnd.google-apps.folder";
	protected static final String BASE_FIELDS = "name,mimeType,id,parents";
	protected static final String COMMON_FIELDS = "name,mimeType,id,parents,md5Checksum";
	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE);
	@Getter
	@Setter
	private String name;
	@Getter
	private boolean connected;
	@Setter
	private String callbackBaseUrl;
	@Setter
	private Integer callbackPort = 8888;
	@Setter
	private String tokensDirectoryPath = "tokens";
	@Setter
	private String credentialFilePath = "credentials.json";
	@Getter
	private String authUrl;
	@Getter
	@Setter
	private String error;
	@Getter
	private CompletableFuture<GoogleDrive> connectedFuture;
	private Drive driveService;
	private DataStore<StoredCredential> credentialDataStored;
	@Setter
	private BiConsumer<String, String> browserConsumer;
	
	public GoogleDrive() {
		connectedFuture = new CompletableFuture<>();
	}

	public void clearConnection() {
		this.connected = false;
		if (this.credentialDataStored != null) {
			try {
				this.credentialDataStored.clear();
			} catch (IOException e) {
				log.error("ignored", e);
			}
		}
	}

	private Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
		String credentialPath = "credentials.json";
		InputStream in = new FileInputStream(credentialPath);
		GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
		if (credentialDataStored == null) {
			credentialDataStored = new FileDataStoreFactory(new java.io.File("googleDrives" 
					+ java.io.File.separator + name + java.io.File.separator + "token")).getDataStore(CREDENTIAL_STORE_ID);
		}
		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
				.setCredentialDataStore(credentialDataStored)
				.setAccessType("offline")
				.setApprovalPrompt("force")
				.build();
		CustomGoogleAuthCodeReceiver receiver = new CustomGoogleAuthCodeReceiver.Builder().setPort(callbackPort)
				.build();
		receiver.createRedirectUri();
		if (callbackBaseUrl != null && !"".equals(callbackBaseUrl) && !"localhost".equals(callbackBaseUrl)) {
			String redirectUrl = receiver.getRedirectUri();
			redirectUrl = redirectUrl.replace("http://localhost:" + callbackPort, callbackBaseUrl);
			receiver.setRedirectUri(redirectUrl);
		}
		return new AuthorizationCodeInstalledApp(flow, receiver, new BiddfluxBrowser()).authorize("user");
	}

	@Async
	public void connectToDrive() throws GeneralSecurityException, IOException {
		if (!this.connected) {
			final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
			driveService = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
					.setApplicationName(APPLICATION_NAME)
					.build();

			this.connected = true;
			this.authUrl = null;
			this.error = null;
			connectedFuture.complete(this);
		} else {
			connectedFuture.complete(this);
		}
	}

	public Files files() {
		return driveService.files();
	}

	public File upload(GoogleInputStreamContent content, File rootDir) throws IOException {
		File fileMetadata = new File();
		fileMetadata.setName(content.getName());
		fileMetadata.setParents(Arrays.asList(rootDir.getId()));
		File file = files().create(fileMetadata, content)
				.setFields(GoogleDrive.COMMON_FIELDS)
				.execute();
		return file;
	}

	public FileResource directDownload(File file) {
		ByteArrayOutputStream outs = new ByteArrayOutputStream();
		MediaHttpDownloader downloader = new MediaHttpDownloader(driveService.getRequestFactory().getTransport(),
				driveService.getRequestFactory().getInitializer());
		downloader.setDirectDownloadEnabled(true);
		downloader.setProgressListener(new LoggingFileDownloadProgressListener(file.getName()));
		try {
			downloader.download(files().get(file.getId()).buildHttpRequestUrl(), outs);
			return new FileResource(null, file.getName(), file.getName(), file.getMimeType(), false, outs.size(), new ByteArrayInputStream(outs.toByteArray()));
		} catch (IOException e) {
			throw Exceptions.server("unable-to-download-file").withExtra("fileName", file.getName()).withCause(e).get();
		}
	}

	public File rename(File f, String newName) throws IOException {
		File nf = new File();
		nf.setName(newName);
		File renamed = files().update(f.getId(), nf)
				.setFields(COMMON_FIELDS)
				.execute();
		return renamed;
	}

	public File move(File f, File target) throws IOException {
		File movedFile = files().update(f.getId(), null)
				.setAddParents(target.getId())
				.setRemoveParents(f.getParents().stream().collect(Collectors.joining(",")))
				.setFields(COMMON_FIELDS)
				.execute();
		return movedFile;
	}

	public void delete(File f) throws IOException{
		files().delete(f.getId()).execute();
	}

	public File getFile(String path) throws IOException {
		String[] pathComponents = path.split("/");
		File file = null;
		for (String pc : pathComponents) {
			if (!"".equals(pc)) {
				file = getFileInFolder(pc, file);
				if (file == null) {
					return null;
				}
			}
		}
		return file;
	}

	public boolean fileExists(String path) {
		try {
			return getFile(path) != null;
		} catch (IOException e) {
			log.error("eror in fileExists(" + path + ")", e);
			this.connected = false;
		}
		return false;
	}

	public File getFileInFolder(String name, File parent) throws IOException {
		String queryStr = "name = '" + name + "'";
		if (parent != null) {
			queryStr += " and '" + parent.getId() + "' in parents";
		}
		List<File> res = files().list().setQ(queryStr).setFields("files(" + COMMON_FIELDS + ")").execute().getFiles();
		if (res.isEmpty()) {
			return null;
		}
		if (res.size() != 1) {
			throw new RuntimeException(queryStr + " : path query returned " + res.size() + " results");
		}
		return res.get(0);
	}

	public File createFolder(String name, File parent) throws IOException {
		File folderMetaData = new File();
		folderMetaData.setName(name);
		folderMetaData.setMimeType(FOLDER_MIMETYPE);
		if (parent != null) {
			folderMetaData.setParents(Arrays.asList(parent.getId()));
		}
		File file = driveService.files().create(folderMetaData)
				.setFields(COMMON_FIELDS)
				.execute();
		return file;
	}

	private class BiddfluxBrowser implements Browser {
		@Override
		public void browse(String url) throws IOException {
			log.debug("open url :{}", url);
			authUrl = url;
			if (callbackBaseUrl != null && !"".equals(callbackBaseUrl) && !"localhost".equals(callbackBaseUrl)) {
				authUrl = url.replace("http://localhost:" + callbackPort + "/", callbackBaseUrl);
			}
			log.debug("auth url :{}", authUrl);
			if(browserConsumer != null){
				browserConsumer.accept(name, authUrl);
			}
		}
	}

	private class LoggingFileDownloadProgressListener implements MediaHttpDownloaderProgressListener {
		private String filePath;
		public LoggingFileDownloadProgressListener(String filePath){
			this.filePath = filePath;
		}
		@Override
		public void progressChanged(MediaHttpDownloader downloader) {
			switch (downloader.getDownloadState()) {
				case NOT_STARTED:
					log.info("download {} not started yet", filePath);
				case MEDIA_IN_PROGRESS:
					log.info("download {} is in progress: {}", filePath, downloader.getProgress());
					break;
				case MEDIA_COMPLETE:
					log.info("download {} is Complete!", filePath);
					break;
			}
		}
	}
}
