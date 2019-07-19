package cn.xnatural.enet.demo;


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
import cn.xnatural.enet.server.http.netty.NettyHttp;
import cn.xnatural.enet.server.remote.Remoter;
import cn.xnatural.enet.server.resteasy.NettyResteasy;
import cn.xnatural.enet.server.sched.SchedServer;
import cn.xnatural.enet.server.session.MemSessionManager;
import cn.xnatural.enet.server.session.RedisSessionManager;
import cn.xnatural.enet.server.swagger.OpenApiDoc;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author xiangxb, 2018-12-22
 */
public class Launcher extends ServerTpl {


    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new NettyHttp(8080));
        app.addSource(new NettyResteasy().scan(RestTpl.class));
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
    void envConfigured() {
        if (ctx.env().getBoolean("session.enabled", false)) {
            String t = ctx.env().getString("session.type", "memory");
            // 根据配置来启动用什么session管理
            if ("memory".equalsIgnoreCase(t)) ctx.addSource(new MemSessionManager());
            else if ("redis".equalsIgnoreCase(t)) ctx.addSource(new RedisSessionManager());
        }
        createAopFn();
        ctx.addSource(TestService.class);
        ctx.addSource(FileUploader.class);
    }


    /**
     * 创建aop函数
     * @return
     */
    void createAopFn() {
        ep.fire("addAop", Async.class, new Function<Map, Supplier>() {
            @Override
            public Supplier apply(Map attr) {
                return () -> {
                    exec.execute(() -> ((Supplier) attr.get("fn")).get());
                    return null;
                };
            }
        });
        ep.fire("addAop", Monitor.class, new Function<Map, Supplier>() {
            @Override
            public Supplier apply(Map attr) {
                return () -> {
                    Method m = (Method) attr.get("method");
                    Supplier fn = (Supplier) attr.get("fn");
                    Object[] args = (Object[]) attr.get("args");
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
                            if (args.length > 0) {
                                sb.append("(");
                                for (int i = 0; i < args.length; i++) {
                                    String s;
                                    if (args[i].getClass().isArray()) s = Arrays.toString((Object[]) args[i]);
                                    else s = Objects.toString(args[i], "");

                                    if (i == 0) sb.append(s);
                                    else sb.append("; ").append(s);
                                }
                                sb.append(")");
                            }
                            sb.append(". time: ").append(end - start).append("ms");
                            if (warn) log.warn(sb.append(anno.logSuffix()).toString());
                            else log.info(sb.append(anno.logSuffix()).toString());
                        }
                    }
                    return ret;
                };
            }
        });
    }


    /**
     * 系统启动结束后执行
     */
    @EL(name = "sys.started")
    void sysStarted() {
        // bean(GroovyEngine.class).script("test.groovy", script -> script.run());
        // ctx.stop(); // 测试关闭
    }
}
