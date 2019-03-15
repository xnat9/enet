package org.xnatural.enet.test;


import org.xnatural.enet.core.AppContext;
import org.xnatural.enet.core.Environment;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.server.cache.ehcache.EhcacheServer;
import org.xnatural.enet.server.dao.hibernate.HibernateServer;
import org.xnatural.enet.server.http.netty.Netty4HttpServer;
import org.xnatural.enet.server.mview.MViewServer;
import org.xnatural.enet.server.resteasy.Netty4ResteasyServer;
import org.xnatural.enet.server.sched.SchedServer;
import org.xnatural.enet.server.session.MemSessionManager;
import org.xnatural.enet.server.swagger.SwaggerServer;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author xiangxb, 2018-12-22
 */
public class Launcher extends ServerTpl {


    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new Netty4HttpServer());
        app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
        app.addSource(new MViewServer());
        app.addSource(new SwaggerServer());
        app.addSource(new HibernateServer().scanEntity(TestEntity.class).scanRepo(TestRepo.class));
        app.addSource(new EhcacheServer());
        app.addSource(new SchedServer());
        app.addSource(new Launcher(app));
        app.start();
    }


    AppContext ctx;

    public Launcher(AppContext ctx) {
        setName("launcher");
        this.ctx = ctx;
    }


    /**
     * 环境配置完成后执行
     * @param ec
     */
    @EL(name = "env.configured", async = false)
    private void envConfigured(EC ec) {
        Environment env = ((Environment) ec.source());
        String t = env.getString("session.type", "memory");
        // 动态启动服务
        if ("memory".equalsIgnoreCase(t)) coreEp.fire("sys.addSource", new MemSessionManager());
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
