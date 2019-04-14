package cn.xnatural.enet.test;


import cn.xnatural.enet.common.Utils;
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
import com.mongodb.*;
import com.netflix.appinfo.*;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import org.xnatural.enet.core.AppContext;

import javax.annotation.Resource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static cn.xnatural.enet.common.Utils.*;
import static com.netflix.appinfo.DataCenterInfo.Name.Netflix;

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


    protected Function<Class<?>, ?> createAopFn() {
        Map<Class, BiFunction<Method, Supplier<Object>, Object>> aopFn = new HashMap<>();
        aopFn.put(Trans.class, (method, fn) -> { // 事务方法拦截
            if (method.getAnnotation(Trans.class) != null) {
                return bean(TransWrapper.class).trans(fn);
            } else return fn.get();
        });
        aopFn.put(Async.class, (method, fn) -> { // 异步方法拦截
            if (method.getAnnotation(Async.class) != null) {
                exec.execute(fn::get); return null;
            } else return fn.get();
        });
//        aopFn.put(Monitor.class, (method, fn) -> { // 异步方法拦截
//            Monitor anno = method.getAnnotation(Monitor.class);
//
//            long start = System.currentTimeMillis();
//            Throwable ex = null;
//            Object ret = null;
//            try {
//                ret = fn.get();
//            } catch (Throwable t) {ex = t;}
//            long end = System.currentTimeMillis();
//
//            boolean warn = (end - start >= anno.warnTimeUnit().toMillis(anno.warnTimeOut()));
//            if (anno.trace() || warn) {
//                StringBuilder sb = new StringBuilder(anno.logPrefix());
//                // sb.append(signature.toShortString());
//                sb.append(method.getDeclaringClass().getName()).append(".").append(method.getName());
//                Object[] args = pjp.getArgs();
//                if (anno.printArgs() && args.length > 0) {
//                    sb.append("(");
//                    for (int i = 0; i < args.length; i++) {
//                        if (i == 0) sb.append(Objects.toString(args[i], ""));
//                        else sb.append(";").append(Objects.toString(args[i], ""));
//                    }
//                    sb.append(")");
//                }
//                sb.append(", time: ").append(end - start).append("ms");
//                if (warn) log.warn(sb.append(anno.logSuffix()).toString());
//                else log.info(sb.append(anno.logSuffix()).toString());
//            }
//            if (ex != null) throw ex;
//            return ret;
//        });

        // 多注解拦截
        return clz -> {
            Method m = Utils.findMethod(clz, mm -> mm.getAnnotation(Trans.class) != null || mm.getAnnotation(Async.class) != null);
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
                return aopFn.get(Async.class).apply(method, () ->
                    aopFn.get(Trans.class).apply(method, fn)
                );
            });
        };
    }


    MongoClient mongoClient;
    // @EL(name = "sys.starting")
    protected void mongoClient() {
        Map<String, String> attrs = ctx.env().groupAttr("mongo");
        MongoClientOptions options = MongoClientOptions.builder()
            .connectTimeout(toInteger(attrs.get("connectTimeout"), 3000))
            .socketTimeout(toInteger(attrs.get("socketTimeout"), 3000))
            .maxWaitTime(toInteger(attrs.get("maxWaitTime"), 5000))
            .heartbeatFrequency(toInteger(attrs.get("heartbeatFrequency"), 5000))
            .minConnectionsPerHost(toInteger(attrs.get("minConnectionsPerHost"), 1))
            .connectionsPerHost(toInteger(attrs.get("connectionsPerHost"), 4))
            .build();

        String uri = attrs.getOrDefault("uri", "");
        if (!uri.isEmpty()) {
            mongoClient = new MongoClient(new MongoClientURI(uri, MongoClientOptions.builder(options)));
        } else {
            MongoCredential credential = null;
            if (attrs.containsKey("username")) {
                credential = MongoCredential.createCredential(attrs.getOrDefault("username", ""), attrs.getOrDefault("database", ""), attrs.getOrDefault("password", "").toCharArray());
            }
            if (credential == null) {
                mongoClient = new MongoClient(
                    new ServerAddress(attrs.getOrDefault("host", "localhost"), toInteger(attrs.get("port"), 27017)), options
                );
            } else {
                mongoClient = new MongoClient(
                    new ServerAddress(attrs.getOrDefault("host", "localhost"), toInteger(attrs.get("port"), 27017)), credential, options
                );
            }
        }
        ctx.addSource(mongoClient);
    }


    DiscoveryClient discoveryClient;
    // @EL(name = "sys.starting")
    protected void eurekaClient() {
        Map<String, String> attrs = ctx.env().groupAttr("eureka");
        MyDataCenterInstanceConfig instanceCfg = new MyDataCenterInstanceConfig();
        ApplicationInfoManager manager = new ApplicationInfoManager(instanceCfg,
            InstanceInfo.Builder.newBuilder()
                .setDataCenterInfo(new MyDataCenterInfo(Netflix))
                .setLeaseInfo(LeaseInfo.Builder.newBuilder().setDurationInSecs(90).setRenewalIntervalInSecs(30).build())
                .setInstanceId("192.168.2.100:enet:8080")
                .setAppName(isEmpty(ctx.getName()) ? "enet" : ctx.getName())
                .setVIPAddress("enet").setPort(8080)
                .setHostName("192.168.2.100")
                .build()
        );
        manager.setInstanceStatus(InstanceInfo.InstanceStatus.UP);
        DefaultEurekaClientConfig cfg = new DefaultEurekaClientConfig() {
            @Override
            public boolean shouldFetchRegistry() {
                return toBoolean(attrs.get("fetchRegistry"), false);
            }
            @Override
            public int getRegistryFetchIntervalSeconds() {
                return toInteger(attrs.get("registryFetchIntervalSeconds"), 30);
            }
            @Override
            public boolean shouldRegisterWithEureka() {
                return toBoolean(attrs.get("registerWithEureka"), true);
            }
            @Override
            public List<String> getEurekaServerServiceUrls(String myZone) {
                return Arrays.stream(attrs.get("client.serviceUrl." + myZone).split(",")).filter(s -> s != null && !s.trim().isEmpty()).collect(Collectors.toList());
            }
        };
        discoveryClient = new DiscoveryClient(manager, cfg);
        ctx.addSource(discoveryClient);
    }


    @EL(name = "sys.stopping")
    protected void stop() {
        if (discoveryClient != null) discoveryClient.shutdown();
        if (mongoClient != null) mongoClient.close();
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
        systemLoadMonitor();
    }


    /**
     * 系统负载监控
     */
    private void systemLoadMonitor() {
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
        if (size > e.getCorePoolSize() * 50) {
            ep.fire("sys.load", EC.of(this).sync().args(size / 7));
            log.warn("system is very heavy load running!. {}", "[" + e.toString().split("\\[")[1]);
        } else if (size > e.getCorePoolSize() * 40) {
            ep.fire("sys.load", 8);
            log.warn("system is heavy load running. {}", "[" + e.toString().split("\\[")[1]);
            ep.fire("sched.after", 45, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > size) {
                    ep.fire("sys.load", 50);
                    log.warn("system is heavy(up) load running. {}", "[" + e.toString().split("\\[")[1]);
                } else if (e.getQueue().size() < size) {
                    ep.fire("sys.load", 7);
                    log.warn("system is heavy(down) load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (size > e.getCorePoolSize() * 20) {
            ep.fire("sys.load", 5);
            log.warn("system will heavy load running. {}", "[" + e.toString().split("\\[")[1]);
            ep.fire("sched.after", 30, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > size) {
                    ep.fire("sys.load", 20);
                    log.warn("system will heavy(up) load running. {}", "[" + e.toString().split("\\[")[1]);
                } else if (e.getQueue().size() < size) {
                    ep.fire("sys.load", 4);
                    log.warn("system will heavy(down) load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (size > e.getCorePoolSize() * 10) {
            log.warn("system is litter heavy load running. {}", "[" + e.toString().split("\\[")[1]);
            ep.fire("sched.after", 25, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > size) {
                    ep.fire("sys.load", 7);
                    log.warn("system is litter heavy(up) load running. {}", "[" + e.toString().split("\\[")[1]);
                } else if (e.getQueue().size() < size) {
                    log.warn("system is litter heavy(down) load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (size > e.getCorePoolSize() * 5) {
            log.info("system executor: {}", "[" + e.toString().split("\\[")[1]);
        } else if (log.isDebugEnabled() || log.isTraceEnabled()) {
            log.debug("system executor: {}", "[" + e.toString().split("\\[")[1]);
        }
    }
}
