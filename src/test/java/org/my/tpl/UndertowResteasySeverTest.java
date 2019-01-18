package org.my.tpl;

import org.xnatural.enet.server.mvc.resteasy.RestTpl;
import org.xnatural.enet.server.mvc.resteasy.UndertowResteasySever;

public class UndertowResteasySeverTest {

    public static void main(String[] args) {
        new UndertowResteasySever().addResource(new RestTpl()).start();
    }
}
