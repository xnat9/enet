package org.xnatural.enet.modules.mvc.anno;

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
