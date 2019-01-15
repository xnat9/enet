package org.xnatural.enet.modules.mvc.anno;

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
