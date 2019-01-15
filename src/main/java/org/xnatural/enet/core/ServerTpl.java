package org.xnatural.enet.core;

import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EP;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
     * 1. 属性配置前缀, 2. 事件名字前缀
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
     * 是否正在运行标志
     */
    protected       AtomicBoolean       running = new AtomicBoolean(false);
    /**
     * 1. 当此服务被加入核心时, 此值会自动设置为核心的EP.
     * 2. 如果要服务独立运行时, 请手动设置
     */
    protected       EP                  coreEp;


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


    /**
     * 初始化一个内部 {@link Executor}
     */
    protected void initExecutor() {
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
