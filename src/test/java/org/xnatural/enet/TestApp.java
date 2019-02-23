package org.xnatural.enet;


import org.xnatural.enet.common.Utils;
import org.xnatural.enet.core.AppContext;
import org.xnatural.enet.core.Environment;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.server.cache.ehcache.EhcacheServer;
import org.xnatural.enet.server.dao.hibernate.HibernateServer;
import org.xnatural.enet.server.http.netty.Netty4HttpServer;
import org.xnatural.enet.server.mvc.resteasy.Netty4ResteasyServer;
import org.xnatural.enet.server.mview.MViewServer;
import org.xnatural.enet.server.sched.SchedServer;
import org.xnatural.enet.server.session.MemSessionManager;
import org.xnatural.enet.server.swagger.SwaggerServer;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author xiangxb, 2018-12-22
 */
public class TestApp extends ServerTpl {


    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new Netty4HttpServer().setPort(8080));
        app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
        app.addSource(new MViewServer());
        app.addSource(new SwaggerServer());
        app.addSource(new HibernateServer().scan(TestEntity.class));
        app.addSource(new EhcacheServer());
        app.addSource(new SchedServer());
        app.addSource(new TestApp(app));
        app.start();
    }


    AppContext ctx;

    public TestApp(AppContext ctx) {
        setName("starter");
        this.ctx = ctx;
    }


    /**
     * 环境配置完成后执行
     * @param ec
     */
    @EL(name = "env.configured", async = false)
    private void init(EC ec) {
        Environment env = ((Environment) ec.source());
        String t = env.getString("server.session.type", "memory");
        // 动态启动服务
        if ("memory".equalsIgnoreCase(t)) coreEp.fire("sys.addSource", new MemSessionManager());
    }


    /**
     * 系统启动结束后执行
     */
    @EL(name = "sys.started")
    private void sysStarted() {
        // Utils.sleep(1000);
        // ctx.stop();
    }


    @EL(name = "sched.started")
    private void jobsInit() {
        try {
            Field f = AppContext.class.getDeclaredField("exec");
            f.setAccessible(true);
            Object v = f.get(ctx);
            if (v instanceof ThreadPoolExecutor) {
                coreEp.fire("sched.cron", "31 */2 * * * ?", (Runnable) () -> monitorExec((ThreadPoolExecutor) v));
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
        if (e.getQueue().size() > 1200) {
            log.warn("system is very heavy load running. {}", "[" + e.toString().split("\\[")[1]);
        } else if (e.getQueue().size() > 700) {
            coreEp.fire("sched.time", 45, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > 1000) {
                    log.warn("system is heavy load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (e.getQueue().size() > 200) {
            coreEp.fire("sched.time", 30, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > 500) {
                    log.warn("system will heavy load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (e.getQueue().size() > 20) {
            coreEp.fire("sched.time", 25, TimeUnit.SECONDS, (Runnable) () -> {
                if (e.getQueue().size() > 70) {
                    log.warn("system is litter heavy load running. {}", "[" + e.toString().split("\\[")[1]);
                }
            });
        } else if (e.getQueue().size() > e.getCorePoolSize()) {
            log.info("system executor: {}", "[" + e.toString().split("\\[")[1]);
        }
    }
}
