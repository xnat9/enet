package org.xnatural.enet.server.mvc.cutome.anno;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestBody {
    /**
     * 是否必须
     * @return
     */
    boolean required() default true;
}
