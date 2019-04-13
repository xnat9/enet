package cn.xnatural.enet.server.dao.hibernate;

import java.lang.annotation.*;

/**
 * 事务方法注解
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Trans {
}
