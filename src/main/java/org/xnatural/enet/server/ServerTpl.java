package org.xnatural.enet.server;

import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 模块 模板 代码.
 * 自定义模块时, 可参考此类代码按需copy
 */
public class ServerTpl {
    protected final Log                 log   = Log.of(getClass());
    /**
     * 服务名字标识
     */
    private         String              name;
    /**
     * 服务命令空间. 一般系统中有多个服务, 用于区别各个服务的配置空间
     * 约定以server.为前缀: 例: server.http1, server.http2, server.mvc, server.ws, server.bls, server.mview, server.sched, server.session.jdbc, server.session.redis, server.dao
     * 1. 用于属性配置前缀
     * 2. 用于事件名字前缀
     */
    private         String              ns;
    /**
     * 可配置属性集.
     */
    protected       Map<String, Object> attrs = new HashMap<>();
    /**
     * 此服务执行器
     */
    protected       Executor            coreExec;
    /**
     * 1. 当此服务被加入核心时, 此值会自动设置为核心的EP.
     * 2. 如果要服务独立运行时, 请手动设置
     */
    protected       EP                  coreEp;
    /**
     * 是否正在运行标志
     */
    protected       AtomicBoolean       running = new AtomicBoolean(false);



//    @EL(name = "sys.starting")
//    public final void start() {
//        if (!running.compareAndSet(false, true)) {
//            log.warn("服务({})正在运行", getName()); return;
//        }
//        if (exec == null) initExecutor();
//        if (coreEp == null) coreEp = new EP(exec);
//    }
//
//
//    @EL(name = "sys.stopping")
//    public final void stop() {
//        if (!sharedExecutor && exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
//    }


    @EL(name = "server.${name}.info")
    protected Map<String, Object> info() throws Exception {
        Map<String, Object> r = new HashMap<>(5);
        r.put("_this", this);

        // 属性
        List<Map<String, Object>> properties = new LinkedList<>(); r.put("properties", properties);
        for (PropertyDescriptor pd : Introspector.getBeanInfo(getClass()).getPropertyDescriptors()) {
            Map<String, Object> prop = new HashMap<>(2); properties.add(prop);
            prop.put("name", pd.getName());
            prop.put("set", pd.getWriteMethod() != null);
            prop.put("type", pd.getPropertyType());
            try {
                prop.put("value", pd.getReadMethod().invoke(this));
            } catch (Exception e) {
                log.warn(e, "属性取值错误. name: {}", pd.getName());
            }
        }

        // 方法
        List<Map<String, Object>> methods = new LinkedList<>(); r.put("methods", methods);
        Class c = getClass();
        do {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() > 0) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (!Modifier.isPublic(m.getModifiers())) continue;
                Map<String, Object> method = new HashMap<>(2); methods.add(method);
                method.put("name", m.getName());
                method.put("annotations", Arrays.stream(m.getAnnotations()).map(a -> "@" + a.getClass().getSimpleName()).collect(Collectors.toList()));
            }
            c = c.getSuperclass();
        } while (c != null);
        return r;
    }


    /**
     * 初始化一个内部 {@link Executor}
     */
    protected void initExecutor() {
        if (coreEp instanceof ExecutorService) {
            log.warn("关闭之前的线程池");
            ((ExecutorService) coreEp).shutdown();
        }
        log.debug("为服务({})创建私有线程池. ", getName());
        ThreadPoolExecutor e = new ThreadPoolExecutor(
                4, 4, 10, TimeUnit.MINUTES, new LinkedBlockingQueue<>(100000),
                new ThreadFactory() {
                    final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, getName() + "-" + count.getAndIncrement());
                    }
                }
        );
        e.allowCoreThreadTimeOut(true);
        coreExec = e;
    }


    public ServerTpl setName(String name) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新服务名");
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("服务标识名不能为空");
        this.name = name;
        setNs("server." + name);
        return this;
    }


    public boolean isRunning() {
        return running.get();
    }


    public String getName() {
        return name;
    }


    public ServerTpl setNs(String ns) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新服务命令空间");
        this.ns = ns;
        return this;
    }


    public String getNs() {
        return ns;
    }


    public EP getCoreEp() {
        return coreEp;
    }


    public Long getLong(String name, Long defaultValue) {
        return Utils.toLong(attrs.get(name), defaultValue);
    }


    public Integer getInteger(String name, Integer defaultValue) {
        return Utils.toInteger(attrs.get(name), defaultValue);
    }


    public Boolean getBoolean(String name, Boolean defaultValue) {
        return Utils.toBoolean(attrs.get(name), defaultValue);
    }


    public Object getAttr(String name, Object defaultValue) {
        return attrs.getOrDefault(name, defaultValue);
    }
}
