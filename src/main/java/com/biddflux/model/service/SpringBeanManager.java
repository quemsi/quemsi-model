package com.biddflux.model.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.biddflux.EnvironmentVars;
import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.dto.StorageType;
import com.biddflux.model.flow.Timer;
import com.biddflux.model.flow.db.mysql.DataSourceFactoryMySql;
import com.biddflux.model.flow.out.GoogleDrive;
import com.biddflux.model.flow.out.Gstorage;
import com.biddflux.model.flow.out.LStorage;
import com.biddflux.model.flow.out.LocalDrive;
import com.biddflux.model.flow.out.Storage;
import com.biddflux.model.flow.retention.RetentionPolicy;

@Service
public class SpringBeanManager {
	@Autowired
	private EnvironmentVars envVars;
	@Autowired
	private DefaultListableBeanFactory beanFactory;
	@Autowired
	protected ApplicationContext context;

	public Timer findTimer(String name){
		return beanFactory.getBean(name, Timer.class);
	}
	
	public List<Timer> findTimers(){
		return List.copyOf(beanFactory.getBeansOfType(Timer.class).values());
	}

	public Timer registerTimer(String name, String schedule) {
		BeanReqisterer<Timer> registerer = new BeanReqisterer<>(name, Timer.class, () -> new Timer());
		Timer t = registerer.getBean();
		t.setName(name);  
		t.setSchedule(schedule);
		registerer.register();
		return t;
	}
	
	public void registerDatasource(String name, String dbName, String url, String username, String password) {
		BeanReqisterer<DataSourceFactoryMySql> registerer = new BeanReqisterer<>(name, DataSourceFactoryMySql.class, () -> new DataSourceFactoryMySql());
		DataSourceFactoryMySql mysql = registerer.getBean();
		mysql.setName(name);
		mysql.setDbName(dbName);
		mysql.setUrl(url);
		mysql.setUsername(username);
		mysql.setPassword(password);
		registerer.register();
	}
	
	public void registerGoogleDrive(String name, String callbackBaseUrl, Integer callbackPort) {
		BeanReqisterer<GoogleDrive> registerer = new BeanReqisterer<>(name, GoogleDrive.class, () -> new GoogleDrive());
		GoogleDrive googleDrive = registerer.getBean();
		googleDrive.setName(name);
		googleDrive.setCredentialFilePath(envVars.getGoogleDriveFilesRoot() + "/" + name);
		googleDrive.setTokensDirectoryPath(envVars.getGoogleDriveFilesRoot() + "/" + name);
		googleDrive.setCallbackBaseUrl(callbackBaseUrl);
		googleDrive.setCallbackPort(callbackPort);
		registerer.register();
	}

	public void registerLocalDrive(String name, String storageRoot, long capacity) {
		BeanReqisterer<LocalDrive> registerer = new BeanReqisterer<>(name, LocalDrive.class, () -> new LocalDrive());
		LocalDrive localDrive = registerer.getBean();
		localDrive.setName(name);
		localDrive.setStorageRoot(storageRoot);
		localDrive.setCapacity(capacity);
		registerer.register();
	}

	public List<Storage> findStorages(){
		return List.copyOf(beanFactory.getBeansOfType(Storage.class).values());
	}

	public Storage findStorage(String name){
		return beanFactory.getBean(name, Storage.class);
	}

	public void registerStroge(String name, StorageType type, String loc, String rootPath, RetentionPolicy retentionPolicy) {
		if(StorageType.GDRIVE.equals(type)){
			BeanReqisterer<Gstorage> registerer = new BeanReqisterer<>(name, Gstorage.class, () -> new Gstorage());
			Gstorage gstorage = registerer.getBean();
			gstorage.setName(name);
			gstorage.setApplyVersion(false);
			gstorage.setRootPath(rootPath);
			gstorage.setGoogleDrive(beanFactory.getBean(loc, GoogleDrive.class));
			gstorage.setRetentionPolicy(retentionPolicy);
			retentionPolicy.setStorage(gstorage);
			registerer.register();
		} else if (StorageType.LOCAL.equals(type)){
			BeanReqisterer<LStorage> registerer = new BeanReqisterer<>(name, LStorage.class, () -> new LStorage());
			LStorage of = registerer.getBean();
			of.setName(name);
			of.setLocalDrive(beanFactory.getBean(loc, LocalDrive.class));
			of.setApplyVersion(false);
			of.setRootPath(rootPath);
			of.setRetentionPolicy(retentionPolicy);
			retentionPolicy.setStorage(of);
			registerer.register();
		} else {
			throw Exceptions.server("not-implemented-yet").withExtra("type", type).get();
		}
	}

	private class BeanReqisterer<T>{
		private String name;
		private Class<T> clazz;
		private T bean = null;
		private Supplier<T> instanceSupplier;
		private boolean newBean = false;
		public BeanReqisterer(String name, Class<T> clazz, Supplier<T> insSupplier){
			this.name = name;
			this.clazz = clazz;
			this.instanceSupplier = insSupplier;
		}
		public T getBean(){
			if(beanFactory.containsBean(name)){
				bean = beanFactory.getBean(name, clazz);
			}else{
				bean = instanceSupplier.get();
				newBean = true;
			}
			return bean;
		}
		public void register(){
			if(newBean){
				beanFactory.autowireBean(bean);
				beanFactory.registerSingleton(name, bean);
			}
		}
	}
}
