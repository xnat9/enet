package org.xnatural.enet;


import org.xnatural.enet.core.AppContext;
import org.xnatural.enet.server.http.netty.Netty4HttpServer;
import org.xnatural.enet.server.mvc.resteasy.RestTpl;
import org.xnatural.enet.server.mview.MViewServer;
import org.xnatural.enet.server.mvc.resteasy.Netty4ResteasyServer;

/**
 * @author xiangxb, 2018-12-22
 */
public class TestApp {

    public static void main(String[] args) {
        AppContext app = new AppContext();
//        app.addSource(new MVCServer().scan(RestTpl.class));
        // app.addSource(new NettyServer().scan(RestTpl.class));
        // app.addSource(new UndertowResteasySever().scan(RestTpl.class));
        // app.addSource(new UndertowResteasySever());
        app.addSource(new Netty4HttpServer());
        app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
        app.addSource(new MViewServer());
        app.start();
    }
}
