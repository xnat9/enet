package org.xnatural.enet.test;


import com.mongodb.*;
import org.xnatural.enet.core.AppContext;
import org.xnatural.enet.core.Environment;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.server.cache.ehcache.EhcacheServer;
import org.xnatural.enet.server.dao.hibernate.Hibernate;
import org.xnatural.enet.server.http.netty.NettyHttp;
import org.xnatural.enet.server.mview.MViewServer;
import org.xnatural.enet.server.redis.RedisServer;
import org.xnatural.enet.server.resteasy.NettyResteasy;
import org.xnatural.enet.server.sched.SchedServer;
import org.xnatural.enet.server.session.MemSessionManager;
import org.xnatural.enet.server.session.RedisSessionManager;
import org.xnatural.enet.server.swagger.SwaggerServer;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.xnatural.enet.common.Utils.toInteger;

/**
 * @author xiangxb, 2018-12-22
 */
public class Launcher extends ServerTpl {


    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new NettyHttp());
        app.addSource(new NettyResteasy().scan(RestTpl.class));
        app.addSource(new MViewServer());
        app.addSource(new SwaggerServer());
        app.addSource(new Hibernate().scanEntity(TestEntity.class).scanRepo(TestRepo.class));
        app.addSource(new SchedServer());
        app.addSource(new EhcacheServer());
        app.addSource(new RedisServer());
        // app.addSource(new XMemcached());
        // app.addSource(new MongoClient("localhost", 27017));
        app.addSource(new Launcher(app));
        app.start();
    }


    AppContext ctx;

    public Launcher(AppContext ctx) {
        setName("launcher");
        this.ctx = ctx;
    }


    // @EL(name = "sys.starting")
    protected void mongoClient() {
        Map<String, String> attrs = ctx.env().groupAttr("mongo");
        MongoClient client = null;
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
            client = new MongoClient(new MongoClientURI(uri, MongoClientOptions.builder(options)));
        } else {
            MongoCredential credential = null;
            if (attrs.containsKey("username")) {
                credential = MongoCredential.createCredential(attrs.getOrDefault("username", ""), attrs.getOrDefault("database", ""), attrs.getOrDefault("password", "").toCharArray());
            }
            if (credential == null) {
                client = new MongoClient(
                    new ServerAddress(attrs.getOrDefault("host", "localhost"), toInteger(attrs.get("port"), 27017)), options
                );
            } else {
                client = new MongoClient(
                    new ServerAddress(attrs.getOrDefault("host", "localhost"), toInteger(attrs.get("port"), 27017)), credential, options
                );
            }
        }
        ctx.addSource(client);
    }


    /**
     * 环境配置完成后执行
     * @param ec
     */
    @EL(name = "env.configured", async = false)
    private void envConfigured(EC ec) {
        Environment env = ((Environment) ec.source());
        String t = env.getString("session.type", "memory");
        // 根据配置来启动用什么session管理
        if ("memory".equalsIgnoreCase(t)) coreEp.fire("sys.addSource", new MemSessionManager());
        else if ("redis".equalsIgnoreCase(t)) coreEp.fire("sys.addSource", new RedisSessionManager());
    }


    /**
     * 系统启动结束后执行
     */
    @EL(name = "sys.started")
    private void sysStarted() {
        // ctx.stop();
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
                coreEp.fire("sched.cron", "31 */1 * * * ?", (Runnable) () -> {
                    monitorExec(v);
                });
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
        if (e.getQueue().size() > 10000) {
            log.warn("system is very heavy load running!. {}", "[" + e.toString().split("\\[")[1]);
        } else if (e.getQueue().size() > 7000) {
            log.warn("system is heavy load running. {}", "[" + e.toString().split("\\[")[1]);
            coreEp.fire("sched.after", 45, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > 7000) {
                    log.warn("system is heavy(up) load running. {}", "[" + e.toString().split("\\[")[1]);
                } else {
                    log.warn("system is heavy(down) load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (e.getQueue().size() > 3000) {
            log.warn("system will heavy load running. {}", "[" + e.toString().split("\\[")[1]);
            coreEp.fire("sched.after", 30, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > 3000) {
                    log.warn("system will heavy(up) load running. {}", "[" + e.toString().split("\\[")[1]);
                } else {
                    log.warn("system will heavy(down) load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (e.getQueue().size() > 500) {
            log.warn("system is litter heavy load running. {}", "[" + e.toString().split("\\[")[1]);
            coreEp.fire("sched.after", 25, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > 500) {
                    log.warn("system is litter heavy(up) load running. {}", "[" + e.toString().split("\\[")[1]);
                } else {
                    log.warn("system is litter heavy(down) load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (e.getQueue().size() > e.getCorePoolSize() * 2) {
            log.info("system executor: {}", "[" + e.toString().split("\\[")[1]);
        }
    }
}
