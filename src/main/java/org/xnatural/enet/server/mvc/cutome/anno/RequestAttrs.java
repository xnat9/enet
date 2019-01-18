package org.xnatural.enet.server.mvc.cutome.anno;

import java.lang.annotation.*;

/**
 * 所有请求中的属性
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
@Documented
public @interface RequestAttrs {
}
