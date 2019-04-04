package org.xnatural.enet.core;


import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.xnatural.enet.common.Utils.*;

/**
 * 系统运行上下文
 */
public class AppContext {
    protected       Log                 log          = Log.of(AppContext.class);
    /**
     * 系统名字. 用于多个系统启动区别
     */
    protected       String              name;
    /**
     * 系统运行线程池. {@link #initExecutor()}}
     */
    protected       ThreadPoolExecutor  exec;
    /**
     * 事件中心 {@link #initEp()}}
     */
    protected       EP                  ep;
    /**
     * 系统环境配置
     */
    protected       Environment         env;
    /**
     * 服务对象源
     */
    protected       Map<String, Object> sourceMap    = new HashMap();
    /**
     * 启动时间
     */
    protected final Date                startup      = new Date();
    /**
     * jvm关闭钩子
     */
    protected       Thread              shutdownHook = new Thread(() -> stop());


    /**
     * 系统启动
     */
    public void start() {
        log.info("Starting Application on {} with PID {}", getHostname(), getPid());
        if (exec == null) initExecutor();
        // 1. 初始化事件发布器
        ep = initEp(); ep.addListenerSource(this);
        sourceMap.forEach((k, v) -> { setForSource(v); ep.addListenerSource(v); });
        // 2. 设置系统环境
        env = new Environment(); env.setEp(ep); env.loadCfg();
        // 3. 通知所有服务启动
        ep.fire("sys.starting", EC.of(this), (ec) -> {
            log.info("Started Application in {} seconds (JVM running for {})",
                    (System.currentTimeMillis() - startup.getTime()) / 1000.0,
                    ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0
            );
            if (shutdownHook != null) Runtime.getRuntime().addShutdownHook(shutdownHook);
            ep.fire("sys.started", EC.of(this));
        });
    }


    /**
     * 系统停止
     */
    public void stop() {
        // 通知各个模块服务关闭
        ep.fire("sys.stopping", EC.of(this), (ce) -> {
            if (shutdownHook != null) Runtime.getRuntime().removeShutdownHook(shutdownHook);
            exec.shutdown();
        });
    }


    /**
     * 添加对象源
     * {@link #ep} 会找出source对象中所有其暴露的功能. 即: 用 @EL 标注的方法
     * 注: 每个对象源都必须有一个 name 属性标识
     * @param source 不能是一个 Class
     * @return
     */
    @EL(name = "sys.addSource", async = false)
    public void addSource(Object source) {
        if (source == null) return;
        if (source instanceof Class) return;
        Method m = findMethod(source.getClass(), "getName");
        String name;
        if (m == null) { name = getClass().getName() + "@" + Integer.toHexString(source.hashCode()); }
        else name = (String) invoke(m, source);
        if (Utils.isEmpty(name)) { log.warn("Get name property is empty from '{}'", source); return; }
        if ("sys".equalsIgnoreCase(name) || "env".equalsIgnoreCase(name) || "log".equalsIgnoreCase(name)) {
            log.warn("name property cannot equal 'sys', 'env' or 'log' . source: {}", source); return;
        }
        if (sourceMap.containsKey(name)) {
            log.warn("name property '{}' already exist in source: {}", name, sourceMap.get(name)); return;
        }
        sourceMap.put(name, source);
        if (ep != null) { setForSource(source); ep.addListenerSource(source); }
    }


    /**
     * 查找对象
     * @param ec
     * @param beanType
     * @param beanName
     * @return
     */
    @EL(name = {"bean.get", "sys.bean.get"}, async = false, order = 1)
    protected Object findBean(EC ec, Class beanType, String beanName) {
        if (ec.result != null) return ec.result; // 已经找到结果了, 就直接返回

        Object bean = null;
        if (beanName != null && beanType != null) {
            bean = sourceMap.get(beanName);
            if (bean != null && !beanType.isAssignableFrom(bean.getClass())) bean = null;
        } else if (beanName != null && beanType == null) {
            bean = sourceMap.get(beanName);
        } else if (beanName == null && beanType != null) {
            if (beanType.isAssignableFrom(getClass())) bean = this;
            else {
                for (Map.Entry<String, Object> entry : sourceMap.entrySet()) {
                    if (beanType.isAssignableFrom(entry.getValue().getClass())) {
                        bean = entry.getValue(); break;
                    }
                }
            }
        }
        return bean;
    }


    /**
     * 初始化 EP
     * @return
     */
    protected EP initEp() {
        return new EP(exec) {
            @Override
            protected Object doPublish(String eName, EC ec, Consumer<EC> completeFn) {
                if ("sys.starting".equals(eName) || "sys.stopping".equals(eName) || "sys.started".equals(eName)) {
                    if (ec.source() != AppContext.this) throw new UnsupportedOperationException("not allow fire event '" + eName + "'");
                }
                if ("env.updateAttr".equals(eName)) {
                    if (ec.source() != env) throw new UnsupportedOperationException("not allow fire event '" + eName + "'");
                }
                return super.doPublish(eName, ec, completeFn);
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
        exec = new ThreadPoolExecutor(
                4, 8, 60, TimeUnit.MINUTES, new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    final AtomicInteger i = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "sys-" + i.getAndIncrement());
                    }
                }
        ) {
            @Override
            public void execute(Runnable fn) {
                try {
                    super.execute(fn);
                } catch (RejectedExecutionException ex) {
                    log.warn("thread pool rejected new task very heavy load. {}", this);
                } catch (Throwable t) {
                    log.error("task happen error", t);
                }
            }
        };
        exec.allowCoreThreadTimeOut(true);
    }


    @EL(name = "env.configured")
    protected void envConfigured() {
        // 重置 exec 相关属性
        Integer c = env.getInteger("sys.exec.corePoolSize", null);
        if (c != null) exec.setCorePoolSize(c);
        Integer m = env.getInteger("sys.exec.maximumPoolSize", null);
        if (m != null) exec.setMaximumPoolSize(m);
        // 如果 maximumPoolSize 小于 corePoolSize 则设置为相等
        if (exec.getCorePoolSize() > exec.getMaximumPoolSize()) exec.setMaximumPoolSize(exec.getCorePoolSize());
        Long k = env.getLong("sys.exec.keepAliveTime", null);
        if (k != null) exec.setKeepAliveTime(k, TimeUnit.SECONDS);

        // 添加 ep 跟踪事件
        ep.addTrackEvent(env.getString("ep.track", "").split(","));
    }


    @EL(name = "env.updateAttr")
    protected void updateAttr(String k, String v) {
        if (k.startsWith("sys.exec")) {
            if ("sys.exec.corePoolSize".equals(k)) {
                Integer i = toInteger(v, null);
                if (i == null) throw new IllegalArgumentException("sys.exec.corePoolSize属性值只能是整数");
                exec.setCorePoolSize(i);
            } else if ("sys.exec.maximumPoolSize".equals(k)) {
                Integer i = toInteger(v, null);
                if (i == null) throw new IllegalArgumentException("sys.exec.maximumPoolSize属性值只能是整数");
                exec.setCorePoolSize(i);
            } else if ("sys.exec.keepAliveTime".equals(k)) {
                Long l = toLong(v, null);
                if (l == null) throw new IllegalArgumentException("sys.exec.keepAliveTime属性值只能是整数");
                exec.setKeepAliveTime(l, TimeUnit.SECONDS);
            } else log.warn("not allow change property '{}'", k);
        }
    }


    @EL(name = "sys.info")
    protected Object info(EC ec) {
        Map<String, Object> info = new HashMap<>();
        info.put("modules", new TreeSet<>(sourceMap.keySet()));
        return info;
    }


    /**
     * 为source 设置 coreEp.
     * 为source 设置 coreExec.
     * @param s
     */
    protected void setForSource(Object s) {
        List<Field> epFs = new LinkedList<>();
        List<Field> execFs = new LinkedList<>();
        iterateField(s.getClass(), f -> {
            if (Modifier.isFinal(f.getModifiers())) return;
            if (EP.class.isAssignableFrom(f.getType())) epFs.add(f);
            else if (Executor.class.isAssignableFrom(f.getType())) execFs.add(f);
        });

        // 1. 为source设置公用 EP
        EP ep = wrapEpForSource(s); // 为了安全
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
    protected Executor wrapExecForSource(Object source) {
        return fn -> exec.execute(fn);
    }


    /**
     * 为每个Source包装EP
     * @param source
     * @return
     */
    protected EP wrapEpForSource(Object source) {
        return new EP() {
            @Override
            protected void init(Executor exec) {}
            @Override
            public Object fire(String eName, EC ec, Consumer<EC> completeFn) {
                if (ec.source() == null) ec.source(source);
                return ep.fire(eName, ec, completeFn);
            }
            @Override
            public EP addListenerSource(Object source) {
                ep.addListenerSource(source); return this;
            }
            @Override
            public String toString() {
                return "wrappedCoreEp:" + source.getClass().getSimpleName();
            }
        };
    }


    public Environment env() {
        return env;
    }


    public String getName() {
        return name;
    }


    public AppContext setName(String name) {
        if (exec != null) throw new RuntimeException("Application is running, not allow change");
        this.name = name;
        return this;
    }
}
