package org.xnatural.enet.core;


import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 简单, 稳定, 灵活, 高效
 * 最大程度的解耦. 这样各个模块的界限清楚明了(简单), 各种模块独立运行测试(稳定), 随意组装模块(灵活), 事件网络到各个模块异步执行任务充分利用线程资源(高效)
 *
 * 系统运行上下文
 * 日志系统, 日志配置
 * 读配置文件. 配置app环境. 覆盖日志配置
 * 加载类路径下的组件
 * 所有的server只写执行规则，具体执行逻辑 由外部提供类
 * 接口层server,业务逻辑层server
 * dao层server
 * 保证所有线程都在运行
 * 尽量没有阻塞等待的线程
 * 把系统看成是各个执行节点
 */
public class AppContext {
    protected final Log log = Log.of(getClass());

    protected ThreadPoolExecutor exec;
    protected EP                 ep;
    protected Environment        env;
    protected List<Object>  sources = new LinkedList<>();
    /**
     * 启动时间
     */
    private Date startup = new Date();


    /**
     * 系统启动
     */
    public void start() {
        log.info("Starting Application on {} with PID {}", Utils.getHostname(), Utils.getPid());
        if (exec == null) initExecutor();
        // 1. 创建事件发布器
        ep = initEp();
        ep.addListenerSource(this);
        sources.forEach(s -> { ep.addListenerSource(s); setForSource(s); });
        // 2. 设置系统环境
        env = new Environment(); env.setEp(ep); env.loadCfg();
        ep.fire("sys.starting", EC.of(this), (ce) -> {
            log.info("Started Application in {} seconds (JVM running for {})",
                    (System.currentTimeMillis() - startup.getTime()) / 1000.0,
                    ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0
            );
            ep.fire("sys.started");
        });
    }


    /**
     * 系统停止
     */
    public void stop() {
        // 通知各个模块服务关闭
        ep.fire("sys.stopping", EC.of(this), (ce) -> exec.shutdown());
    }


    /**
     * {@link #ep} 会找出source对象中所有其暴露的功能. 即: 用 @EL 标注的方法
     * @param source 不能是一个 Class
     * @return
     */
    public AppContext addSource(Object source) {
        if (source == null) return this;
        if (source instanceof Class) return this;
        if (source instanceof ServerTpl) {
            for (Object s : sources) {
                if (s instanceof ServerTpl && Objects.equals(((ServerTpl) s).getName(), ((ServerTpl) source).getName())) {
                    throw new IllegalArgumentException("ServerTpl 存在同相的名字. " + ((ServerTpl) s).getName());
                }
            }
        }
        if (ep != null) {
            ep.addListenerSource(source);
            setForSource(source);
        }
        sources.add(source);
        return this;
    }


    /**
     * 初始化 EP
     * @return
     */
    protected EP initEp() {
        return new EP(exec) {
            @Override
            protected void doPublish(String eName, EC ec, Consumer<EC> completeFn) {
                if ("sys.starting".equals(eName) || "sys.stopping".equals(eName)) {
                    if (ec.source() != AppContext.this) throw new UnsupportedOperationException("不允许触发此事件");
                }
                if ("sys.env.updateAttr".equals(eName)) {
                    if (ec.source() != env) throw new UnsupportedOperationException("不允许触发此事件");
                }
                super.doPublish(eName, ec, completeFn);
            }
            @Override
            public String toString() { return "coreEp"; }
        };
    }


    /**
     * 初始化一个 {@link ThreadPoolExecutor}
     * NOTE: 如果线程池在不停的创建线程, 有可能是因为 提交的 Runnable 的异常没有被处理.
     * see:  {@link ThreadPoolExecutor#runWorker(ThreadPoolExecutor.Worker)} 这里面当有异常抛出时 1128行代码 {@link ThreadPoolExecutor#processWorkerExit(ThreadPoolExecutor.Worker, boolean)}
     */
    protected void initExecutor() {
        int capacity = 100000;
        exec = new ThreadPoolExecutor(
                8, 8, 10, TimeUnit.MINUTES, new LinkedBlockingQueue<>(capacity),
                new ThreadFactory() {
                    final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "sys-" + count.getAndIncrement());
                    }
                }
        ) {
            long idleStartTime;
            long idleMinute = TimeUnit.MINUTES.toMillis(5);
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                super.beforeExecute(t, r);
                if (idleStartTime != 0) {
                    if (System.currentTimeMillis() - idleStartTime > idleMinute) {
                        log.info("executor池已空闲 " + ((System.currentTimeMillis() - idleStartTime) / 1000) + " 秒, 现在继续工作");
                    }
                    idleStartTime = 0;
                }
                if (getQueue().size() >= (capacity * 0.6)) log.warn("executor池正在重负运行, " + toString());
            }
            @Override
            public void execute(Runnable command) {
                try {
                    super.execute(command);
                } catch (RejectedExecutionException ex) {
                    log.warn("executor池已不堪重负, " + toString());
                } catch (Exception t) {
                    log.error("executor执行错误", t);
                }
            }
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                super.afterExecute(r, t);
                if (getQueue().size() == 0) idleStartTime = System.currentTimeMillis();
            }
        };
        exec.allowCoreThreadTimeOut(true);
    }


    @EL(name = "sys.env.configured", async = false)
    private void reAdjustExec() {
        Integer c = env.getInteger("sys.exec.corePoolSize", null);
        if (c != null) exec.setCorePoolSize(c);
        Integer m = env.getInteger("sys.exec.maximumPoolSize", null);
        if (m != null) exec.setMaximumPoolSize(m);
        // 如果 maximumPoolSize 小于 corePoolSize 则设置为相等
        if (exec.getCorePoolSize() > exec.getMaximumPoolSize()) exec.setMaximumPoolSize(exec.getCorePoolSize());
        Long k = env.getLong("sys.exec.keepAliveTime", null);
        if (k != null) exec.setKeepAliveTime(k, TimeUnit.MILLISECONDS);
    }


    @EL(name = "sys.env.updateAttr", async = false)
    private void reAdjustExec(EC ec) {
        String k = ec.getAttr("key", String.class);
        String v = ec.getAttr("value", String.class);
        if (k.startsWith("sys.exec")) {
            if ("sys.exec.corePoolSize".equals(k)) {
                Integer i = Utils.toInteger(v, null);
                if (i == null) throw new IllegalArgumentException("sys.exec.corePoolSize属性值只能是整数");
                exec.setCorePoolSize(i);
            } else if ("sys.exec.maximumPoolSize".equals(k)) {
                Integer i = Utils.toInteger(v, null);
                if (i == null) throw new IllegalArgumentException("sys.exec.maximumPoolSize属性值只能是整数");
                exec.setCorePoolSize(i);
            } else if ("sys.exec.keepAliveTime".equals(k)) {
                Long l = Utils.toLong(v, null);
                if (l == null) throw new IllegalArgumentException("sys.exec.keepAliveTime属性值只能是整数");
                exec.setKeepAliveTime(l, TimeUnit.MILLISECONDS);
            } else log.warn("不允许更新属性: " + k);
        }
    }


    @EL(name = "sys.info")
    private Object info(EC ec) {
        Map<String, Object> info = new HashMap<>(2);
        info.put("modules", sources.stream().filter(o -> o instanceof ServerTpl).map(o -> ((ServerTpl) o).getName()).collect(Collectors.toList()));
        return info;
    }


    /**
     * 为source 设置 coreEp.
     * 为source 设置 coreExec.
     * @param s
     */
    private void setForSource(Object s) {
        List<Field> epFs = new LinkedList<>();
        List<Field> execFs = new LinkedList<>();
        Class<? extends Object> c = s.getClass();
        do {
            for (Field f : c.getDeclaredFields()) {
                if (EP.class.isAssignableFrom(f.getType())) epFs.add(f);
                else if (Executor.class.isAssignableFrom(f.getType())) execFs.add(f);
            }
            c = c.getSuperclass();
        } while (c != null);

        // 1. 为source设置公用 EP
        EP ep = wrapEpForSource(s); //为了安全
        if (epFs.size() == 1) {
            Field f = epFs.get(0);
            try {
                f.setAccessible(true); f.set(s, ep);
            } catch (Exception ex) {
                log.error(ex);
            }
        } else if (epFs.size() > 1) { // 有多个 EP, 则只设置 字段名字为 coreEp
            for (Field f : epFs) {
                if ("coreEp".equals(f.getName())) {
                    try {
                        f.setAccessible(true); f.set(s, ep);
                    } catch (Exception ex) {
                        log.error(ex);
                    }
                    break;
                }
            }
        }

        // 2. 为source 设置公用 Executor
        Executor exec = wrapExecForSource(s);
        if (execFs.size() == 1) {
            Field f = execFs.get(0);
            try {
                f.setAccessible(true); f.set(s, exec);
            } catch (Exception ex) {
                log.error(ex);
            }
        } else if (execFs.size() > 1) { // 有多个 Executor, 则只设置 字段名字为 coreExec
            for (Field f : execFs) {
                if ("coreExec".equals(f.getName())) {
                    try {
                        f.setAccessible(true); f.set(s, exec);
                    } catch (Exception ex) {
                        log.error(ex);
                    }
                    break;
                }
            }
        }
    }


    /**
     * 为 source 包装 Executor
     * @param source
     * @return
     */
    private Executor wrapExecForSource(Object source) {
        return fn -> exec.execute(fn);
    }


    /**
     * 为每个Source包装EP
     * @param source
     * @return
     */
    private EP wrapEpForSource(Object source) {
        return new EP() {
            @Override
            public void fire(String eName, EC ec, Consumer<EC> completeFn) {
                if (ec.source() == null) ec.setSource(source);
                ep.fire(eName, ec, completeFn);
            }
            @Override
            public EP addListenerSource(Object source) {
                ep.addListenerSource(source);
                return this;
            }
            @Override
            public String toString() {
                return "wrappedCoreEp:" + source.getClass().getSimpleName();
            }
        };
    }
}
