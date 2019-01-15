package org.xnatural.enet.modules.mvc.anno;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
@Documented
public @interface SessionAttr {
    /**
     * session 中的属性名
     * @return
     */
    String name() default "";

    /**
     * 是否必须
     * @return
     */
    boolean required() default true;
}
