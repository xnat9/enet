package org.xnatural.enet.server.mvc.cutome.anno;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
@Documented
public @interface RequestHeader {
    /**
     * 请求header名
     * @return
     */
    String name() default "";
}
