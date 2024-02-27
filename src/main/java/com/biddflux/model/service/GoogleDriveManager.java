package com.biddflux.model.service;

import java.util.List;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.stereotype.Service;

import com.biddflux.model.flow.out.GoogleDrive;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GoogleDriveManager {
	//TODO: how this should work in agent
	// @Autowired
	// private GoogleDriveServiceImpl googleDriveServiceImpl;
	@Autowired
	private DefaultListableBeanFactory beanFactory;
	@Autowired
	@Qualifier("googleDriveExecutor")
	private ExecutorService googleDriveExecutor;

	public void connectToDrives() {
		// googleDriveServiceImpl.findAll().forEach(gde -> {
		// 	googleDriveExecutor.execute(()->{
		// 		GoogleDrive gd = beanFactory.getBean(gde.getName(), GoogleDrive.class);
		// 		try {
		// 			gd.connectToDrive();
		// 		} catch (Exception e) {
		// 			log.error("error connection to drive ", e);
		// 			gd.setError(StringUtils.stackTraceOf(e));
		// 		}
		// 	});
		// });
	}
	
	public List<GoogleDrive> googleDriveBeans() {
		return List.copyOf(beanFactory.getBeansOfType(GoogleDrive.class).values());
	}
}
