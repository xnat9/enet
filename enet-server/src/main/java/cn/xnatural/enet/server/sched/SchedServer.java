package cn.xnatural.enet.server.sched;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.spi.ThreadPool;
import cn.xnatural.enet.common.Utils;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.xnatural.enet.common.Utils.isEmpty;

/**
 * 时间任务调度器
 * @author xiangxb, 2019-02-16
 */
public class SchedServer extends ServerTpl {
    protected final AtomicBoolean running = new AtomicBoolean(false);
    @Resource
    protected       Executor      exec;
    protected       Scheduler     scheduler;


    public SchedServer() { super("sched"); }
    public SchedServer(String name) { super(name); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (ep == null) ep = new EP(exec);
        ep.fire(getName() + ".starting");
        // 先从核心取配置, 然后再启动
        attrs.putAll((Map) ep.fire("env.ns", getName()));
        try {
            StdSchedulerFactory f = new StdSchedulerFactory();
            Properties p = new Properties(); p.putAll(attrs);
            p.setProperty(StdSchedulerFactory.PROP_THREAD_POOL_CLASS, AgentThreadPool.class.getName());
            f.initialize(p);
            AgentThreadPool.exec = exec;
            scheduler = f.getScheduler();
            scheduler.start();
            exposeBean(scheduler);
        } catch (SchedulerException e) { throw new RuntimeException(e); }
        ep.fire(getName() + ".started");
        log.info("Started {}(Quartz) Server", getName());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}(Quartz)' Server", getName());
        try {
            if (scheduler != null) scheduler.shutdown();
        } catch (SchedulerException e) {
            log.error(e);
        }
        AgentThreadPool.exec = null;
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    /**
     * cron 时间表达式
     * @param cron
     * @param fn
     */
    @EL(name = "sched.cron")
    public void sched(String cron, Runnable fn) {
        if (!running.get()) return;
        if (isEmpty(cron) || fn == null) throw new IllegalArgumentException("'cron' and 'fn' must not be empty");
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


    /**
     * 在多少时间之后执行
     * @param time
     * @param unit
     * @param fn
     */
    @EL(name = "sched.after")
    public void sched(Integer time, TimeUnit unit, Runnable fn) {
        if (!running.get()) return;
        if (time == null || unit == null || fn == null) throw new NullPointerException("'time', 'unit' and 'fn' must not be null");
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
            log.debug("add after '{}' job will execute at '{}'", id, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss SSS").format(d));
        } catch (SchedulerException e) {
            log.error(e, "add after job error! time: {}, unit: {}", time, unit);
        }
    }


    /**
     * 在将来的某个时间点执行
     * @param time
     * @param fn
     */
    @EL(name = "sched.time")
    public void sched(Date time, Runnable fn) {
        if (!running.get()) return;
        if (time == null || fn == null) throw new NullPointerException("'time' and 'fn' must not be null");
        JobDataMap data = new JobDataMap();
        data.put("fn", fn);
        String id = time + "_" + System.currentTimeMillis();
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("ss mm HH dd MM ? yyyy");
            String cron = sdf.format(time);
            Date d = scheduler.scheduleJob(
                    JobBuilder.newJob(JopTpl.class).withIdentity(id).setJobData(data).build(),
                    TriggerBuilder.newTrigger()
                            .withIdentity(new TriggerKey(id, "default"))
                            .withSchedule(CronScheduleBuilder.cronSchedule(cron))
                            .build()
            );
            log.info("add time '{}' job will execute at '{}'", id, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss SSS").format(d));
        } catch (SchedulerException e) {
            log.error(e, "add time job error! time: {}", time);
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
        public boolean runInThread(Runnable fn) {
            if (exec == null) fn.run();
            else exec.execute(fn);
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
