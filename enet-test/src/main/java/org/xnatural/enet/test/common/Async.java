package org.xnatural.enet.test.common;

import java.lang.annotation.*;

/**
 * 异步方法注解
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Async {
}
