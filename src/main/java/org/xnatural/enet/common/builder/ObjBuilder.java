package org.xnatural.enet.common.builder;

import org.apache.commons.beanutils.BeanUtils;
import org.xnatural.enet.common.Context;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.server.mvc.cutome.ApiResp;

import java.lang.reflect.InvocationTargetException;
import java.util.*;


/**
 * JavaBean对象构建器, 从一个运行上下文中, 构建一个结果对象.
 * 一般用于: rest 接口{@link ApiResp#data}, 即service 方法返回一个对象, 对象的一些属性需要复杂计算的结果
 *
 * @param <T>
 * @author hubert
 */
public class ObjBuilder<T> extends AbstractBuilder<T> {
    /**
     * 一个javaBean.
     */
    private       Class<T>                javaBeanClz;
    /**
     * 属性的计算是可以依赖顺序的(先计算某个属性, 再根据这个属性的值, 计算另一个属性的值)
     * NOTE: 尽量每个属性的计算不会相互依赖
     */
    private final Map<String, Builder<?>> propertyGenerators = new LinkedHashMap<>();


    public static final <T> ObjBuilder<T> of(Class<T> javaBeanClz) {
        return new ObjBuilder<T>().setJavaBeanClz(javaBeanClz);
    }


    @Override
    protected boolean isValid(Context ctx) {
        if (!super.isValid(ctx)) return false;
        if (getJavaBeanClz() == null) {
            log.error("property javaBeanClz must not be null");
            return false;
        }
        if (Utils.isEmpty(propertyGenerators)) log.warn("fieldGenerators is empty!");
        return true;
    }


    @Override
    protected T doBuild(Context ctx) {
        T retObj = instance(ctx);
        if (retObj == null) return null;
        propertyGenerators.forEach((propName, builder) -> {
            if (builder == null) {
                log.warn("属性 " + propName + " 对应的Builder为空!");
                return;
            }
            if (builder instanceof MultiPropertyBuilder) {
                Map<String, Object> propertyValues = ((MultiPropertyBuilder) builder).build(ctx);
                log.debug("builder: {} populate propertyValues: {}", builder, propertyValues);
                try {
                    BeanUtils.populate(retObj, propertyValues);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    log.error(e, "populate propertyValues: " + propertyValues + " for bean: " + retObj.getClass());
                }
            } else {
                if (propName == null || propName.isEmpty()) {
                    log.warn("属性名为空, 忽略!");
                    return;
                }
                Object value = builder.build(ctx);
                log.debug("builder: {} populate value: {} for property: {}", builder, value, propName);
                try {
                    BeanUtils.setProperty(retObj, propName, value);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    log.error(e, "set property: " + propName + " value: " + value + " for bean: " + retObj.getClass());
                }
            }
        });
        return retObj;
    }


    public ObjBuilder<T> add(String propName, Builder builder) {
        if (builder instanceof MultiPropertyBuilder && propName != null && !propName.isEmpty()) {
            log.warn("MultiPropertyGenerator 对应多个属性所以不需要有属性名: ({}), 请用add(MultiPropertyGenerator generator)", propName);
        }
        propertyGenerators.put(propName, builder);
        return this;
    }


    public ObjBuilder<T> add(MultiPropertyBuilder generator) {
        propertyGenerators.put(UUID.randomUUID().toString(), generator);
        return this;
    }


    /**
     * create instance for DTOClass.
     *
     * @param ctx GeneratorContext
     * @return target instance.
     */
    @SuppressWarnings("unchecked")
    protected T instance(Context ctx) {
        T targetObj = null;
        Class<T> targetClass = getJavaBeanClz();
        try {
            if (Map.class.equals(targetClass)) {
                targetObj = (T) new LinkedHashMap<>();
            } else if (Set.class.equals(targetClass)) {
                targetObj = (T) new LinkedHashSet<>();
            } else if (List.class.equals(targetClass)) {
                targetObj = (T) new ArrayList<>();
            } else {
                targetObj = targetClass.newInstance();
            }
        } catch (InstantiationException | IllegalAccessException e) {
            log.error(e, "create instance error, dtoClass: " + targetClass);
        }
        return targetObj;
    }


    public Class<T> getJavaBeanClz() {
        return javaBeanClz;
    }


    public ObjBuilder setJavaBeanClz(Class<T> pDTOClass) {
        javaBeanClz = pDTOClass;
        return this;
    }
}
