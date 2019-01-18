package org.xnatural.enet.server.mvc.cutome.anno;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
@Documented
public @interface RequestAttr {
    /**
     * 请求的属性名
     * @return
     */
    String name() default "";
}
