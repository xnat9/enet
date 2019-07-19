package cn.xnatural.enet.demo.common;

import java.lang.annotation.*;

/**
 * 异步方法注解
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface Async {
}
