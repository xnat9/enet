package org.xnatural.enet.server.resteasy;

import java.lang.annotation.*;

/**
 * @author xiangxb, 2019-03-02
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SessionAttr {
    String value();
}
