package cn.xnatural.enet.demo;


import cn.xnatural.enet.common.Utils;
import cn.xnatural.enet.core.AppContext;
import cn.xnatural.enet.demo.common.Async;
import cn.xnatural.enet.demo.common.Monitor;
import cn.xnatural.enet.demo.dao.entity.TestEntity;
import cn.xnatural.enet.demo.dao.repo.TestRepo;
import cn.xnatural.enet.demo.rest.RestTpl;
import cn.xnatural.enet.demo.service.FileUploader;
import cn.xnatural.enet.demo.service.TestService;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.server.ServerTpl;
import cn.xnatural.enet.server.cache.ehcache.EhcacheServer;
import cn.xnatural.enet.server.dao.hibernate.Hibernate;
import cn.xnatural.enet.server.dao.hibernate.Trans;
import cn.xnatural.enet.server.dao.hibernate.TransWrapper;
import cn.xnatural.enet.server.http.netty.NettyHttp;
import cn.xnatural.enet.server.mview.MViewServer;
import cn.xnatural.enet.server.remote.Remoter;
import cn.xnatural.enet.server.resteasy.NettyResteasy;
import cn.xnatural.enet.server.sched.SchedServer;
import cn.xnatural.enet.server.session.MemSessionManager;
import cn.xnatural.enet.server.session.RedisSessionManager;
import cn.xnatural.enet.server.swagger.OpenApiDoc;

import javax.annotation.Resource;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Executor;
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
        // app.addSource(new RedisClient());
        // app.addSource(new XMemcached());
        // app.addSource(new MongoClient("localhost", 27017));
        app.addSource(new Remoter());
        app.addSource(new Launcher());
        app.start();
    }


    @Resource
    AppContext      ctx;
    @Resource
    Executor        exec;


    /**
     * 环境配置完成后执行
     */
    @EL(name = "env.configured", async = false)
    private void envConfigured() {
        if (ctx.env().getBoolean("session.enabled", false)) {
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
    private Function<Class<?>, ?> createAopFn() {
        abstract class AopFnCreator {
            abstract Supplier<Object> create(Method m, Object[] args, Supplier<Object> fn);
        }
        // 注解->注解的逻辑Creator
        Map<Class, AopFnCreator> aopFnCreator = new HashMap<>();
        aopFnCreator.put(Trans.class, new AopFnCreator() { // 事务方法拦截
            @Override
            Supplier<Object> create(Method m, Object[] args, Supplier<Object> fn) {
                return () -> bean(TransWrapper.class).trans(fn);
            }
        });
        aopFnCreator.put(Async.class, new AopFnCreator() { // 异步方法拦截
            @Override
            Supplier<Object> create(Method m, Object[] args, Supplier<Object> fn) {
                return () -> { exec.execute(fn::get); return null; };
            }
        });
        aopFnCreator.put(Monitor.class, new AopFnCreator() { // 方法监视执行
            @Override
            Supplier<Object> create(Method m, Object[] args, Supplier<Object> fn) {
                return () -> {
                    Monitor anno = m.getAnnotation(Monitor.class);
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
                };
            }
        });

        // 多注解拦截
        return clz -> {
            // 查询是否有aop注解的方法
            Method m = Utils.findMethod(clz, mm -> {
                for (Iterator<Class> it = aopFnCreator.keySet().iterator(); it.hasNext(); ) {
                    if (mm.getAnnotation(it.next()) != null) return true;
                }
                return false;
            });
            if (m == null) { // 如果没有直接new对象
                try { return clz.newInstance(); } catch (Exception e) { log.error(e); }
                return null;
            }
            // 创建被代理对象函数
            return proxy(clz, (obj, method, args, proxy) -> {
                // 被代理的方法执行原始逻辑
                Supplier fn = () -> {
                    try { return proxy.invokeSuper(obj, args); }
                    catch (Throwable t) { throw new RuntimeException(t); }
                };
                // 为原始逻辑创建aop执行逻辑
                Annotation[] arr = method.getAnnotations(); // 最外面包裹最里面执行
                for (int i = arr.length - 1; i >= 0; i--) {
                    AopFnCreator creator = aopFnCreator.get(arr[i].annotationType());
                    if (creator == null) continue;
                    fn = creator.create(method, args, fn);
                }
                return fn.get();
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
}
