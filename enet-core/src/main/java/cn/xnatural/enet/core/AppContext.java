package cn.xnatural.enet.core;


import cn.xnatural.enet.common.Log;
import cn.xnatural.enet.common.Utils;
import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;

import javax.annotation.Resource;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static cn.xnatural.enet.common.Utils.*;

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
        // 1. 初始化系统线程池
        if (exec == null) initExecutor();
        // 2. 初始化事件中心
        ep = initEp(); ep.addListenerSource(this);
        sourceMap.forEach((k, v) -> { inject(v); ep.addListenerSource(v); }); // 先添加服务是为了可以监听到 env.configured 事件
        // 3. 初始化系统环境
        env = new Environment(ep); env.loadCfg();
        // 4. 通知所有服务启动
        ep.fire("sys.starting", EC.of(this), (ec) -> {
            if (shutdownHook != null) Runtime.getRuntime().addShutdownHook(shutdownHook);
            autoInject();
            log.info("Started Application in {} seconds (JVM running for {})",
                (System.currentTimeMillis() - startup.getTime()) / 1000.0,
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0
            );
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
        if (source == null || source instanceof Class) return;
        Method m = findMethod(source.getClass(), mm -> Modifier.isPublic(mm.getModifiers()) && "getName".equals(mm.getName()) && mm.getParameterCount() == 0 && String.class.equals(mm.getReturnType()));
        String name;
        if (m == null) {
            name = source.getClass().getSimpleName().replace("$$EnhancerByCGLIB$$", "@");
            name = name.substring(0, 1).toLowerCase() + name.substring(1);
        } else name = (String) invoke(m, source);
        if (Utils.isEmpty(name)) { log.warn("Get name property is empty from '{}'", source); return; }
        if ("sys".equalsIgnoreCase(name) || "env".equalsIgnoreCase(name) || "log".equalsIgnoreCase(name)) {
            log.warn("Name property cannot equal 'sys', 'env' or 'log' . source: {}", source); return;
        }
        if (sourceMap.containsKey(name)) {
            log.warn("Name property '{}' already exist in source: {}", name, sourceMap.get(name)); return;
        }
        sourceMap.put(name, source);
        if (ep != null) { inject(source); ep.addListenerSource(source); }
    }


    /**
     * 初始化. 自动为所有对象源注入所需的对象
     */
    protected void autoInject() {
        log.debug("Auto inject @Resource field");
        sourceMap.forEach((s, o) -> inject(o));
    }


    /**
     * 为bean对象中的{@link javax.annotation.Resource}注解字段注入对应的bean对象
     * @param o
     */
    @EL(name = "inject", async = false)
    protected void inject(Object o) {
        iterateField(o.getClass(), f -> {
            Resource r = f.getAnnotation(Resource.class);
            if (r == null) return;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                if (v != null) return; // 已经存在值则不需要再注入

                // 取值
                if (EP.class.isAssignableFrom(f.getType())) v = wrapEpForSource(o);
                else if (Executor.class.isAssignableFrom(f.getType())) v = wrapExecForSource(o);
                else if (Environment.class.isAssignableFrom(f.getType())) v = env;
                else if (AppContext.class.isAssignableFrom(f.getType())) v = this;
                else v = ep.fire("bean.get", EC.of(this).sync().args(f.getType(), r.name())); // 全局获取bean对象

                if (v == null) return;
                f.set(o, v);
                log.trace("Inject @Resource field '{}' for object '{}'", f.getName(), o);
            } catch (Exception e) { log.error(e); }
        });
    }


    /**
     * 查找对象
     * @param ec
     * @param beanType bean的类型
     * @param beanName bean 名字
     * @return bean 对象
     */
    @EL(name = {"bean.get", "sys.bean.get"}, async = false, order = 1)
    protected Object findLocalBean(EC ec, Class beanType, String beanName) {
        if (ec.result != null) return ec.result; // 已经找到结果了, 就直接返回

        Object bean = null;
        if (isNotEmpty(beanName) && beanType != null) {
            bean = sourceMap.get(beanName);
            if (bean != null && !beanType.isAssignableFrom(bean.getClass())) bean = null;
        } else if (isNotEmpty(beanName) && beanType == null) {
            bean = sourceMap.get(beanName);
        } else if (isEmpty(beanName) && beanType != null) {
            for (Map.Entry<String, Object> entry : sourceMap.entrySet()) {
                if (beanType.isAssignableFrom(entry.getValue().getClass())) {
                    bean = entry.getValue(); break;
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
                    log.warn("Thread pool rejected new task very heavy load. {}", this);
                } catch (Throwable t) {
                    log.error("Task happen unknown error", t);
                }
            }
        };
        exec.allowCoreThreadTimeOut(true);
    }


    @EL(name = "env.configured")
    protected void envConfigured() {
        //1. 重置 exec 相关属性
        Integer c = env.getInteger("sys.exec.corePoolSize", null);
        if (c != null) exec.setCorePoolSize(c);
        Integer m = env.getInteger("sys.exec.maximumPoolSize", null);
        if (m != null) exec.setMaximumPoolSize(m);
        Long k = env.getLong("sys.exec.keepAliveTime", null);
        if (k != null) exec.setKeepAliveTime(k, TimeUnit.SECONDS);

        //2. 添加 ep 跟踪事件
        ep.addTrackEvent(env.getString("ep.track", "").split(","));
    }


    @EL(name = "env.updateAttr")
    protected void updateAttr(String k, String v) {
        if (k.startsWith("sys.exec")) { // 更改sys线程池属性
            if ("sys.exec.corePoolSize".equals(k)) {
                Integer i = toInteger(v, null);
                if (i == null) throw new IllegalArgumentException("'sys.exec.corePoolSize' only can be int. " + v);
                exec.setCorePoolSize(i);
            } else if ("sys.exec.maximumPoolSize".equals(k)) {
                Integer i = toInteger(v, null);
                if (i == null) throw new IllegalArgumentException("'sys.exec.maximumPoolSize' only can be int. " + v);
                exec.setCorePoolSize(i);
            } else if ("sys.exec.keepAliveTime".equals(k)) {
                Long l = toLong(v, null);
                if (l == null) throw new IllegalArgumentException("'sys.exec.keepAliveTime' only can be int. " + v);
                exec.setKeepAliveTime(l, TimeUnit.SECONDS);
            } else log.warn("Not allow change property '{}'", k);
        }
    }


    @EL(name = "sys.info")
    protected Object info() {
        Map<String, Object> info = new HashMap<>();
        info.put("modules", new TreeSet<>(sourceMap.keySet()));
        return info;
    }


    /**
     * 为 source 包装 Executor
     * @param source
     * @return {@link Executor}
     */
    protected Executor wrapExecForSource(Object source) {
        return new Executor() {
            @Override
            public void execute(Runnable cmd) { exec.execute(cmd); }
            public int getCorePoolSize() { return exec.getCorePoolSize(); }
            public int getWaitingCount() { return exec.getQueue().size(); }
        };
    }


    /**
     * 为每个Source包装EP
     * @param source
     * @return {@link EP}
     */
    protected EP wrapEpForSource(Object source) {
        return new EP() {
            @Override
            protected void init(Executor exec) {}
            @Override
            public EP addTrackEvent(String... eNames) { ep.addTrackEvent(eNames); return this; }
            @Override
            public EP delTrackEvent(String... eNames) { ep.delTrackEvent(eNames); return this; }
            @Override
            public EP removeEvent(String eName, Object s) {
                if (source != null && s != null && source != s) throw new UnsupportedOperationException("Only allow remove event of this source: " + source);
                ep.removeEvent(eName, s); return this;
            }
            @Override
            public EP addListenerSource(Object source) { ep.addListenerSource(source); return this; }
            @Override
            public Object fire(String eName, EC ec, Consumer<EC> completeFn) {
                if (ec.source() == null) ec.source(source);
                return ep.fire(eName, ec, completeFn);
            }
            @Override
            public String toString() {
                return "wrappedCoreEp:" + source.getClass().getSimpleName();
            }
        };
    }


    public Environment env() { return env; }


    public String getName() { return name; }


    public AppContext setName(String name) {
        if (exec != null) throw new RuntimeException("Application is running, not allow change");
        this.name = name;
        return this;
    }
}
