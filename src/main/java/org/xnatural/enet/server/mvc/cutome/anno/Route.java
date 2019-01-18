package org.xnatural.enet.server.mvc.cutome.anno;

import java.lang.annotation.*;


@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Route {
    /**
     * 描述
     * @return
     */
    String desc() default "";

    /**
     * 映射的请求路径
     * @return
     */
    String[] path() default {};

    /**
     * get,post. 大小写都可以
     * @return
     */
    String[] method() default {};

    /**
     * 请求接收的 content-type
     * @return
     */
    String[] consumes() default {};

    /**
     * 请求响应的 content-type
     * @return
     */
    String produces() default "text/plan";
}
