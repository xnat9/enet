package cn.xnatural.enet.test;


import cn.xnatural.enet.common.Utils;
import cn.xnatural.enet.core.AppContext;
import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.server.ServerTpl;
import cn.xnatural.enet.server.cache.ehcache.EhcacheServer;
import cn.xnatural.enet.server.dao.hibernate.Hibernate;
import cn.xnatural.enet.server.dao.hibernate.Trans;
import cn.xnatural.enet.server.dao.hibernate.TransWrapper;
import cn.xnatural.enet.server.http.netty.NettyHttp;
import cn.xnatural.enet.server.mview.MViewServer;
import cn.xnatural.enet.server.resteasy.NettyResteasy;
import cn.xnatural.enet.server.sched.SchedServer;
import cn.xnatural.enet.server.session.MemSessionManager;
import cn.xnatural.enet.server.session.RedisSessionManager;
import cn.xnatural.enet.server.swagger.OpenApiDoc;
import cn.xnatural.enet.test.common.Async;
import cn.xnatural.enet.test.common.Monitor;
import cn.xnatural.enet.test.dao.entity.TestEntity;
import cn.xnatural.enet.test.dao.repo.TestRepo;
import cn.xnatural.enet.test.rest.RestTpl;
import cn.xnatural.enet.test.service.FileUploader;
import cn.xnatural.enet.test.service.TestService;

import javax.annotation.Resource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static cn.xnatural.enet.common.Utils.proxy;

/**
 * @author xiangxb, 2018-12-22
 */
public class Launcher extends ServerTpl {


    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new NettyHttp().setPort(8080));
        app.addSource(new NettyResteasy().scan(RestTpl.class));
        app.addSource(new MViewServer());
        app.addSource(new OpenApiDoc());
        app.addSource(new Hibernate().scanEntity(TestEntity.class).scanRepo(TestRepo.class));
        app.addSource(new SchedServer());
        app.addSource(new EhcacheServer());
        // app.addSource(new RedisServer());
        // app.addSource(new XMemcached());
        // app.addSource(new MongoClient("localhost", 27017));
        app.addSource(new Launcher());
        app.start();
    }


    @Resource
    AppContext ctx;
    @Resource
    Executor   exec;


    /**
     * 环境配置完成后执行
     */
    @EL(name = "env.configured", async = false)
    private void envConfigured() {
        if (Utils.toBoolean(ep.fire("session.isEnabled"), false)) {
            String t = ctx.env().getString("session.type", "memory");
            // 根据配置来启动用什么session管理
            if ("memory".equalsIgnoreCase(t)) ctx.addSource(new MemSessionManager());
            else if ("redis".equalsIgnoreCase(t)) ctx.addSource(new RedisSessionManager());
        }
        Function<Class<?>, ?> wrap = createAopFn();
        ctx.addSource(wrap.apply(TestService.class));
        ctx.addSource(wrap.apply(FileUploader.class));
    }


    /**
     * 创建aop函数
     * @return
     */
    protected Function<Class<?>, ?> createAopFn() {
        abstract class AopFn {
            abstract Object run(Method m, Object[] args, Supplier<Object> fn);
        }
        Map<Class, AopFn> aopFn = new HashMap<>();
        aopFn.put(Trans.class, new AopFn() { // 事务方法拦截
            @Override
            Object run(Method m, Object[] args, Supplier<Object> fn) {
                if (m.getAnnotation(Trans.class) != null) {
                    return bean(TransWrapper.class).trans(fn);
                } else return fn.get();
            }
        });
        aopFn.put(Async.class, new AopFn() { // 异步方法拦截
            @Override
            Object run(Method m, Object[] args, Supplier<Object> fn) {
                if (m.getAnnotation(Async.class) != null) {
                    exec.execute(fn::get); return null;
                } else return fn.get();
            }
        });
        aopFn.put(Monitor.class, new AopFn() { // 方法监视执行
            @Override
            Object run(Method m, Object[] args, Supplier<Object> fn) {
                Monitor anno = m.getAnnotation(Monitor.class);
                if (anno == null) return fn.get();

                long start = System.currentTimeMillis();
                Object ret = null;
                try { ret = fn.get();}
                catch (Throwable t) { throw t; }
                finally {
                    long end = System.currentTimeMillis();
                    boolean warn = (end - start >= anno.warnTimeUnit().toMillis(anno.warnTimeOut()));
                    if (anno.trace() || warn) {
                        StringBuilder sb = new StringBuilder(anno.logPrefix());
                        sb.append(m.getDeclaringClass().getName()).append(".").append(m.getName());
                        if (anno.printArgs() && args.length > 0) {
                            sb.append("(");
                            for (int i = 0; i < args.length; i++) {
                                String s;
                                if (args[i].getClass().isArray()) s = Arrays.toString((Object[]) args[i]);
                                else s = Objects.toString(args[i], "");

                                if (i == 0) sb.append(s);
                                else sb.append(";").append(s);
                            }
                            sb.append(")");
                        }
                        sb.append(", time: ").append(end - start).append("ms");
                        if (warn) log.warn(sb.append(anno.logSuffix()).toString());
                        else log.info(sb.append(anno.logSuffix()).toString());
                    }
                }
                return ret;
            }
        });

        // 多注解拦截
        return clz -> {
            Method m = Utils.findMethod(clz, mm ->
                mm.getAnnotation(Trans.class) != null || mm.getAnnotation(Async.class) != null || mm.getAnnotation(Monitor.class) != null
            );
            if (m == null) {
                try { return clz.newInstance(); }
                catch (Exception e) { log.error(e); }
                return null;
            }
            return proxy(clz, (obj, method, args, proxy) -> {
                Supplier fn = () -> {
                    try { return proxy.invokeSuper(obj, args); }
                    catch (Throwable t) { throw new RuntimeException(t); }
                };
                return aopFn.get(Async.class).run(method, args, () ->
                    aopFn.get(Monitor.class).run(method, args, () ->
                        aopFn.get(Trans.class).run(method, args, fn)
                    )
                );
            });
        };
    }


    /**
     * 系统启动结束后执行
     */
    @EL(name = "sys.started")
    private void sysStarted() {
        // ctx.stop(); // 测试关闭
    }


    @EL(name = "sched.started")
    private void schedStarted() {
        // 系统负载监控
        try {
            Field f = AppContext.class.getDeclaredField("exec");
            f.setAccessible(true);
            ThreadPoolExecutor v = (ThreadPoolExecutor) f.get(ctx);
            if (v instanceof ThreadPoolExecutor) {
                ep.fire("sched.cron", "0 0/1 * * * ?", (Runnable) () -> monitorExec(v));
            }
        } catch (Exception e) {
            log.error(e);
        }

    }


    /**
     * 监控系统线程池的运行情况
     * @param e
     */
    private void monitorExec(ThreadPoolExecutor e) {
        int size = e.getQueue().size();
        AtomicInteger down = new AtomicInteger(0); // 应该拦截多少个任务进线程池
        if (size > e.getCorePoolSize() * 50) {
            down.set(size / 7); log.warn("system is very heavy load running!. {}", "[" + e.toString().split("\\[")[1]);
        } else if (size > e.getCorePoolSize() * 40) {
            log.warn("system is heavy load running. {}", "[" + e.toString().split("\\[")[1]);
            ep.fire("sched.after", 25, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > size) {
                    down.set(20); log.warn("system is heavy(up) load running. {}", "[" + e.toString().split("\\[")[1]);
                } else if (e.getQueue().size() < size) {
                    log.warn("system is heavy(down) load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (size > e.getCorePoolSize() * 20) {
            log.warn("system will heavy load running. {}", "[" + e.toString().split("\\[")[1]);
            ep.fire("sched.after", 10, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > size) {
                    down.set(5); log.warn("system will heavy(up) load running. {}", "[" + e.toString().split("\\[")[1]);
                } else if (e.getQueue().size() < size) {
                    log.warn("system will heavy(down) load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (size > e.getCorePoolSize() * 5) {
            log.info("system executor: {}", "[" + e.toString().split("\\[")[1]);
        } else if (log.isDebugEnabled() || log.isTraceEnabled()) {
            log.debug("system executor: {}", "[" + e.toString().split("\\[")[1]);
        }
        if (down.get() > 0) ep.fire("sys.load", EC.of(this).sync().args(down.get()));
    }
}
