package org.xnatural.enet;


import org.xnatural.enet.common.Log;
import org.xnatural.enet.core.AppContext;
import org.xnatural.enet.core.Environment;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.cache.ehcache.EhcacheServer;
import org.xnatural.enet.server.dao.hibernate.HibernateServer;
import org.xnatural.enet.server.http.netty.Netty4HttpServer;
import org.xnatural.enet.server.mvc.resteasy.Netty4ResteasyServer;
import org.xnatural.enet.server.mview.MViewServer;
import org.xnatural.enet.server.session.MemSessionManager;
import org.xnatural.enet.server.swagger.SwaggerServer;

/**
 * @author xiangxb, 2018-12-22
 */
public class TestApp {

    public static void main(String[] args) {
        AppContext app = new AppContext();
        // app.addSource(new MVCServer().scan(RestTpl.class));
        // app.addSource(new UndertowResteasySever().scan(RestTpl.class));
        // app.addSource(new UndertowResteasySever());

        app.addSource(new Netty4HttpServer().setPort(8081));
        app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
        app.addSource(new MViewServer());
        app.addSource(new SwaggerServer());
        app.addSource(new HibernateServer().scan(TestEntity.class));
        app.addSource(new EhcacheServer());
        app.addSource(new TestApp());
        app.start();
    }


    @EL(name = "env.configured", async = false)
    private void init(EC ec) {
        Environment env = ((Environment) ec.source());
        String t = env.getString("server.session.type", "memory");
        if ("memory".equalsIgnoreCase(t)) ec.ep().fire("sys.addSource", new MemSessionManager());
    }


    @EL(name = {"sys.started"})
    private void staredListen(EC ec) {
        // ((AppContext) ec.source()).stop();
        // ((AppContext) ec.source()).env().setAttr("server.http-netty.port", "8080");
    }


//    public String getNs() {
//        return null;
//    }


    public String getName() {
        return "testApp";
    }
}
