package cn.xnatural.enet.server;

import cn.xnatural.enet.common.Context;
import cn.xnatural.enet.common.Log;
import cn.xnatural.enet.common.Utils;
import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static cn.xnatural.enet.common.Utils.*;

/**
 * 模块 模板 代码.
 * 自定义模块时, 可参考此类代码按需copy
 */
public class ServerTpl {
    protected     Log                 log;
    /**
     * 服务名字标识.(保证唯一)
     * 可用于命名空间:
     * 1. 可用于属性配置前缀
     * 2. 可用于事件名字前缀
     */
    private final String              name;
    /**
     * 可配置属性集.
     */
    protected     Map<String, Object> attrs = new ConcurrentHashMap<>(7);
    /**
     * 1. 当此服务被加入核心时, 此值会自动设置为核心的EP.
     * 2. 如果要服务独立运行时, 请手动设置
     */
    @Resource
    protected     EP                  ep;


    public ServerTpl() {
        String n = getClass().getSimpleName().replace("$$EnhancerByCGLIB$$", "@").split("@")[0];
        this.name = n.substring(0, 1).toLowerCase() + n.substring(1);
        log = Log.of(getClass());
    }
    public ServerTpl(String name) {
        if (isEmpty(name)) throw new IllegalArgumentException("name can not be empty");
        this.name = name;
        log = Log.of(getClass());
    }


    /**
     * bean 容器. {@link #findLocalBean}
     */
    protected Context beanCtx;
    @EL(name = {"bean.get", "${name}.bean.get"}, async = false)
    protected Object findLocalBean(EC ec, Class beanType, String beanName) {
        if (beanCtx == null) return ec.result;
        if (ec.result != null) return ec.result; // 已经找到结果了, 就直接返回

        Object bean = null;
        if (isNotEmpty(beanName) && beanType != null) {
            bean = beanCtx.getAttr(beanName);
            if (bean != null && !beanType.isAssignableFrom(bean.getClass())) bean = null;
        } else if (isNotEmpty(beanName) && beanType == null) {
            bean = beanCtx.getAttr(beanName);
        } else if (isEmpty(beanName) && beanType != null) {
            if (beanType.isAssignableFrom(getClass())) bean = this;
            else bean = beanCtx.getValue(beanType);
        }
        return bean;
    }


    /**
     * 全局查找Bean
     * @param type
     * @param <T>
     * @return
     */
    protected <T> T bean(Class<T> type) {
        return (T) ep.fire("bean.get", type);
    }


    /**
     * 暴露 bean 给其它模块用. {@link #findLocalBean}
     * @param names bean 名字.
     * @param bean
     */
    protected ServerTpl exposeBean(Object bean, String... names) {
        if (bean == null) {
            log.warn("server '{}' expose bean with null object.", getName()); return this;
        }
        if (beanCtx == null) beanCtx = new Context();
        if (names != null) {
            for (String n : names) {
                if (beanCtx.getAttr(n) != null) log.warn("override exist bean name '{}'", n);
                beanCtx.attr(n, bean);
            }
        }
        beanCtx.put(bean);
        return this;
    }


    /**
     * 用于mview server
     * @return
     * @throws Exception
     */
    @EL(name = "server.${name}.info")
    protected Map<String, Object> info() throws Exception {
        Map<String, Object> r = new HashMap<>(5);
        r.put("_this", this);

        // 属性
        List<Map<String, Object>> properties = new LinkedList<>(); r.put("properties", properties);
        List<Map<String, Object>> methods = new LinkedList<>(); r.put("methods", methods);
        AtomicReference<Method> pm = new AtomicReference<>();
        iterateMethod(getClass(),
            m -> { // 取属性
                if (m.getParameterCount() != 0) return;
                if (void.class.isAssignableFrom(m.getReturnType()) || Void.class.isAssignableFrom(m.getReturnType())) return;
                Map<String, Object> prop;
                String suffix; // get, is, set 方法后缀
                if (m.getName().startsWith("get")) {
                    prop = new HashMap<>(7); properties.add(prop);
                    suffix = m.getName().substring(3);
                } else if (m.getName().startsWith("is") && (boolean.class.equals(m.getReturnType()) || Boolean.class.equals(m.getReturnType()))) {
                    prop = new HashMap<>(7); properties.add(prop);
                    suffix = m.getName().substring(2);
                } else return;
                prop.put("name", suffix.substring(0, 1).toLowerCase() + suffix.substring(1));
                prop.put("type", m.getReturnType());
                prop.put("value", invoke(m, this));
                prop.put("settable", ((Supplier<Boolean>) () -> {
                    String n = "set" + suffix;
                    if (int.class.equals(m.getReturnType()) || Integer.class.equals(m.getReturnType())) {
                        Method mm = findMethod(getClass(), n, int.class);
                        if (mm == null) mm = findMethod(getClass(), n, Integer.class);
                        if (mm != null) return true;
                    } else if (long.class.equals(m.getReturnType()) || Long.class.equals(m.getReturnType())) {
                        Method mm = findMethod(getClass(), n, long.class);
                        if (mm == null) mm = findMethod(getClass(), n, Long.class);
                        if (mm != null) return true;
                    } else if (boolean.class.equals(m.getReturnType()) || Boolean.class.equals(m.getReturnType())) {
                        Method mm = findMethod(getClass(), n, boolean.class);
                        if (mm == null) mm = findMethod(getClass(), n, Boolean.class);
                        if (mm != null) return true;
                    } else if (double.class.equals(m.getReturnType()) || Double.class.equals(m.getReturnType())) {
                        Method mm = findMethod(getClass(), n, double.class);
                        if (mm == null) mm = findMethod(getClass(), n, Double.class);
                        if (mm != null) return true;
                    } else if (float.class.equals(m.getReturnType()) || Float.class.equals(m.getReturnType())) {
                        Method mm = findMethod(getClass(), n, float.class);
                        if (mm == null) mm = findMethod(getClass(), n, Float.class);
                        if (mm != null) return true;
                    } else {
                        return findMethod(getClass(), n, m.getReturnType()) != null;
                    }
                    return false;
                }).get());
                pm.set(m);
            },
            m -> { // 取方法
                if (!Modifier.isPublic(m.getModifiers())) return;
                if (m.getParameterCount() > 0) return;
                if (Modifier.isAbstract(m.getModifiers())) return;
                if (m.equals(pm.get())) return; // 是属性方法
                if ("notify".equals(m.getName()) || "notifyAll".equals(m.getName()) || "wait".equals(m.getName()) || "hashCode".equals(m.getName())) return;
                if (methods.stream().anyMatch(e -> m.getName().equals(e.get("name")))) return;
                Map<String, Object> attr = new HashMap<>(2); methods.add(attr);
                attr.put("name", m.getName());
                attr.put("annotations", Arrays.stream(m.getAnnotations()).map(a -> "@" + a.getClass().getSimpleName()).collect(Collectors.toList()));
                attr.put("returnType", m.getReturnType());
                attr.put("invokeReturn", "");
            }
        );
        properties.sort(Comparator.comparing(o -> o.get("name").toString()));
        methods.sort(Comparator.comparing(o -> o.get("name").toString()));
        return r;
    }


    public String getLogLevel() {
        if (log == null) return null;
        else if (log.isTraceEnabled()) return "trace";
        else if (log.isDebugEnabled()) return "debug";
        else if (log.isInfoEnabled()) return "info";
        else if (log.isWarnEnabled()) return "warn";
        else if (log.isErrorEnabled()) return "error";
        return null;
    }


    public void setLogLevel(String level) {
        log.setLevel(level);
    }


    public String getName() {
        return name;
    }


    public EP getEp() {
        return ep;
    }


    public Long getLong(String name, Long defaultValue) {
        return Utils.toLong(attrs.get(name), defaultValue);
    }


    public Integer getInteger(String name, Integer defaultValue) {
        return Utils.toInteger(attrs.get(name), defaultValue);
    }


    public String getStr(String name, String defaultValue) {
        return Objects.toString(attrs.get(name), defaultValue);
    }


    public Boolean getBoolean(String name, Boolean defaultValue) {
        return Utils.toBoolean(attrs.get(name), defaultValue);
    }


    public Object getAttr(String name, Object defaultValue) {
        return attrs.getOrDefault(name, defaultValue);
    }


    public ServerTpl attr(String key, Object value) {
        attrs.put(key, value);
        return this;
    }
}
