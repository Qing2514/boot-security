package com.boot.security.server.service.impl;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.boot.security.server.dao.JobDao;
import com.boot.security.server.job.SpringBeanJob;
import com.boot.security.server.model.JobModel;
import com.boot.security.server.service.JobService;

@Service
public class JobServiceImpl implements JobService {

	private static final Logger log = LoggerFactory.getLogger("adminLogger");

	@Autowired
	private Scheduler scheduler;
	@Autowired
	private ApplicationContext applicationContext;
	private static final String JOB_DATA_KEY = "JOB_DATA_KEY";
	@Autowired
	private JobDao jobDao;

	@Override
	public void saveJob(JobModel jobModel) {
		checkJobModel(jobModel);
		String name = jobModel.getJobName();

		JobKey jobKey = JobKey.jobKey(name);
		JobDetail jobDetail = JobBuilder.newJob(SpringBeanJob.class).storeDurably()
				.withDescription(jobModel.getDescription()).withIdentity(jobKey).build();

		jobDetail.getJobDataMap().put(JOB_DATA_KEY, jobModel);

		CronScheduleBuilder cronScheduleBuilder = CronScheduleBuilder.cronSchedule(jobModel.getCron());
		CronTrigger cronTrigger = TriggerBuilder.newTrigger().withIdentity(name).withSchedule(cronScheduleBuilder)
				.forJob(jobKey).build();

		try {
			boolean exists = scheduler.checkExists(jobKey);
			if (exists) {
				scheduler.rescheduleJob(new TriggerKey(name), cronTrigger);
				scheduler.addJob(jobDetail, true);
			} else {
				scheduler.scheduleJob(jobDetail, cronTrigger);
			}

			JobModel model = jobDao.getByName(name);
			if (model == null) {
				jobDao.save(jobModel);
			} else {
				jobDao.update(jobModel);
			}
		} catch (SchedulerException e) {
			log.error("???????????????job??????", e);
		}
	}

	private void checkJobModel(JobModel jobModel) {
		String springBeanName = jobModel.getSpringBeanName();
		boolean flag = applicationContext.containsBean(springBeanName);
		if (!flag) {
			throw new IllegalArgumentException("bean???" + springBeanName + "????????????bean??????userServiceImpl,???????????????");
		}

		Object object = applicationContext.getBean(springBeanName);
		Class<?> clazz = object.getClass();
		if (AopUtils.isAopProxy(object)) {
			clazz = clazz.getSuperclass();
		}

		String methodName = jobModel.getMethodName();
		Method[] methods = clazz.getDeclaredMethods();

		Set<String> names = new HashSet<>();
		Arrays.asList(methods).forEach(m -> {
			Class<?>[] classes = m.getParameterTypes();
			if (classes.length == 0) {
				names.add(m.getName());
			}
		});

		if (names.size() == 0) {
			throw new IllegalArgumentException("???bean??????????????????");
		}

		if (!names.contains(methodName)) {
			throw new IllegalArgumentException("?????????????????????" + methodName + ",???bean?????????????????????" + names);
		}
	}

	@Override
	public void doJob(JobDataMap jobDataMap) {
		JobModel jobModel = (JobModel) jobDataMap.get(JOB_DATA_KEY);

		String beanName = jobModel.getSpringBeanName();
		String methodName = jobModel.getMethodName();
		Object object = applicationContext.getBean(beanName);

		try {
			log.info("job:bean???{}???????????????{}", beanName, methodName);
			Method method = object.getClass().getDeclaredMethod(methodName);
			method.invoke(object);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * ??????job
	 * 
	 * @throws SchedulerException
	 */
	@Override
	public void deleteJob(Long id) throws SchedulerException {
		JobModel jobModel = jobDao.getById(id);

		if (jobModel.getIsSysJob() != null && jobModel.getIsSysJob()) {
			throw new IllegalArgumentException("???job??????????????????????????????????????????job??????????????????????????????????????????job????????????????????????????????????");
		}

		String jobName = jobModel.getJobName();
		JobKey jobKey = JobKey.jobKey(jobName);

		scheduler.pauseJob(jobKey);
		scheduler.unscheduleJob(new TriggerKey(jobName));
		scheduler.deleteJob(jobKey);

		jobModel.setStatus(0);
		jobDao.update(jobModel);
	}

}
