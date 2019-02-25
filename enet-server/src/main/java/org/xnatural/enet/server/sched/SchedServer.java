package org.xnatural.enet.server.sched;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.spi.ThreadPool;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 时间任务调度器
 * @author xiangxb, 2019-02-16
 */
public class SchedServer extends ServerTpl {

    protected Scheduler scheduler;

    public SchedServer() {
        setName("sched");
    }


    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        // 先从核心取配置, 然后再启动
        Map<String, String> r = (Map) coreEp.fire("env.ns", getName());
        attrs.putAll(r);
        try {
            StdSchedulerFactory f = new StdSchedulerFactory();
            Properties p = new Properties(); p.putAll(attrs);
            p.setProperty(StdSchedulerFactory.PROP_THREAD_POOL_CLASS, AgentThreadPool.class.getName());
            f.initialize(p);
            AgentThreadPool.exec = coreExec;
            scheduler = f.getScheduler();
            scheduler.start();
        } catch (SchedulerException e) { throw new RuntimeException(e); }
        coreEp.fire(getName() + ".started");
        log.info("Started {}(Quartz) Server", getName());
    }


    @Override
    public void stop() {
        log.info("Shutdown '{}(Quartz)' Server", getName());
        try {
            if (scheduler != null) scheduler.shutdown();
        } catch (SchedulerException e) {
            log.error(e);
        }
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    @EL(name = "sched.cron")
    public void sched(String cron, Runnable fn) {
        if (Utils.isEmpty(cron) || fn == null) throw new IllegalArgumentException("参数错误");
        JobDataMap data = new JobDataMap();
        data.put("fn", fn);
        String id = cron + "_" + System.currentTimeMillis();
        try {
            Date d = scheduler.scheduleJob(
                    JobBuilder.newJob(JopTpl.class).withIdentity(id).setJobData(data).build(),
                    TriggerBuilder.newTrigger()
                            .withIdentity(new TriggerKey(id, "default"))
                            .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                            .build()
            );
            log.info("add cron '{}' job will execute last time '{}'", id, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss SSS").format(d));
        } catch (SchedulerException e) {
            log.error(e, "add cron job error! cron: '{}'", cron);
        }
    }


    @EL(name = "sched.time")
    public void sched(Integer time, TimeUnit unit, Runnable fn) {
        if (time == null || unit == null || fn == null) throw new IllegalArgumentException("参数错误");
        JobDataMap data = new JobDataMap();
        data.put("fn", fn);
        String id = time + "_" + unit + "_" + System.currentTimeMillis();
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("ss mm HH dd MM ? yyyy");
            String cron = sdf.format(new Date(new Date().getTime() + unit.toMillis(time)));
            Date d = scheduler.scheduleJob(
                    JobBuilder.newJob(JopTpl.class).withIdentity(id).setJobData(data).build(),
                    TriggerBuilder.newTrigger()
                            .withIdentity(new TriggerKey(id, "default"))
                            .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                            .build()
            );
            log.info("add time '{}' job will execute last time '{}'", id, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss SSS").format(d));
        } catch (SchedulerException e) {
            log.error(e, "add time job error! time: {}, unit: {}", time, unit);
        }
    }


    /**
     * quartz job 公用模板
     */
    public static class JopTpl implements Job {
        @Override
        public void execute(JobExecutionContext ctx) throws JobExecutionException {
            ((Runnable) ctx.getMergedJobDataMap().get("fn")).run();
        }
    }


    /**
     * 代理线程池
     */
    public static class AgentThreadPool implements ThreadPool {
        static Executor exec;
        @Override
        public boolean runInThread(Runnable runnable) {
            exec.execute(runnable);
            return true;
        }

        @Override
        public int blockForAvailableThreads() {
            return 1;
        }

        @Override
        public void initialize() throws SchedulerConfigException { }

        @Override
        public void shutdown(boolean waitForJobsToComplete) { }

        @Override
        public int getPoolSize() {
            return -1;
        }

        @Override
        public void setInstanceId(String schedInstId) { }

        @Override
        public void setInstanceName(String schedName) { }
    }
}
