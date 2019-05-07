package cn.xnatural.enet.demo.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 方法监控注解, 会打印被注解方法的执行信息
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitor {
    /**
     * 方法被执行多久会触发一条warn日志
     *
     * @return
     */
    long warnTimeOut() default 3000;

    TimeUnit warnTimeUnit() default TimeUnit.MILLISECONDS;

    /**
     * 如果为true, 每执行一次方法都会把方法执行的信息打印出来
     *
     * @return
     */
    boolean trace() default false;

    boolean printArgs() default true;

    String logPrefix() default "METHOD PROCESS MONITOR: ";

    String logSuffix() default "";
}
