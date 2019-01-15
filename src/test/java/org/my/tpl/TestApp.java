package org.my.tpl;


import org.xnatural.enet.core.AppContext;
import org.xnatural.enet.modules.mview.MViewServer;
import org.xnatural.enet.modules.resteasy.UndertowResteasySever;

/**
 * @author xiangxb, 2018-12-22
 */
public class TestApp {

    public static void main(String[] args) {
        AppContext app = new AppContext();
//        app.addSource(new NettyHttpServer().setPort(8081));
//        app.addSource(new MVCServer().scan(RestTpl.class));
        // app.addSource(new NettyServer().scan(RestTpl.class));
        // app.addSource(new UndertowResteasySever().scan(RestTpl.class));
        app.addSource(new UndertowResteasySever());
        app.addSource(new MViewServer());
        app.start();
    }
}
