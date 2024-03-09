package com.biddflux.model.flow;

import static org.quartz.CronScheduleBuilder.cronSchedule;
import static org.quartz.TriggerBuilder.newTrigger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;

import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.MethodInvokingJobDetailFactoryBean;

import com.biddflux.commons.util.Exceptions;
import com.biddflux.model.flow.Flow.FlowRunnable;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Timer{
	private static AtomicInteger indexer = new AtomicInteger(1);
	private int index;
	@Setter
	@Autowired
	private Scheduler scheduler;
	@Setter
	@Getter
	private String name;
	@Getter
	@Setter
	private String schedule;
	@Getter
	private boolean initialized;
	private TriggerKey triggerKey;
	
	@Getter
	private Map<String, FlowRunnable> runnables = new HashMap<>();
	public void add(FlowRunnable r) {
		runnables.put(r.getFlowName(), r);
	}
	
	public void tick() {
		log.debug("{} {} timer tick", this.name, this.index);
		if(!runnables.isEmpty()) {
			runnables.values().forEach(Runnable::run);
		}
	}

	public void reset(){
		try {
			scheduler.unscheduleJob(triggerKey);
			init();
		} catch (SchedulerException e) {
			throw Exceptions.server("scheduler-error").withCause(e).get();
		}
	}
	
	@PostConstruct
	public void init() {
		try {
			this.index = indexer.getAndIncrement();
			MethodInvokingJobDetailFactoryBean factory = new MethodInvokingJobDetailFactoryBean();
			factory.setTargetObject(this);
			factory.setTargetMethod("tick");
			factory.afterPropertiesSet();
			String indexedName = this.name + index;
			String groupName = "timersgroup";
			triggerKey = TriggerKey.triggerKey(indexedName , groupName);
        	JobDetail job = factory.getObject();
			Trigger trigger = newTrigger()
				.withIdentity(indexedName, groupName)
            	.withSchedule(
            			cronSchedule(this.schedule)
            			.withMisfireHandlingInstructionFireAndProceed()
            			)
            	.build();
            scheduler.scheduleJob(job, trigger);
            this.initialized = true;
            log.debug("timer {} {} scheduled", this.name, index);
        } catch (Throwable se) {
            log.error("error creating timer " + this.name, se);
        }
	}	
}
