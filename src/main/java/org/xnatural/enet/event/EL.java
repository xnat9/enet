package org.xnatural.enet.event;

import java.lang.annotation.*;

/**
 * 事件监听器. event listener
 * @Note 注意: 这个注解的方法参数要么没有, 要么只有一个 {@link EC}
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EL {
    /**
     * 事件名.可相同. 即: 一个事件可以对应多个监听器
     * 支持 ${attr} 替换
     * @return
     */
    String name();

    /**
     * 是否异步. 默认异步
     * @return
     */
    boolean async() default true;

    /**
     * 同相事件名的多个监听器的执行顺序.
     * 从小到大执行.即: 越小越先执行.
     * NOTE: 当 {@link #async()} 为false时有用
     * @return
     */
    float order() default 0;
}
