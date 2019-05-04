package cn.xnatural.enet.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公用 Context 容器
 *
 * @author hubert
 */
public class Context {
    private final Map<Object, Object> attrs  = new LinkedHashMap<>(7);


    public Context attr(Object pKey, Object pValue) {
        attrs.put(pKey, pValue);
        return this;
    }


    public Context put(Object pValue) {
        if (pValue == null) return this;
        if (pValue instanceof Class) attrs.put(pValue, pValue);
        else attrs.put(pValue.getClass(), pValue);
        return this;
    }


    public Context attrs(Map attrs) {
        this.attrs.putAll(attrs);
        return this;
    }


    public Object getAttr(Object pKey) {
        if (pKey == null) return null;
        return attrs.get(pKey);
    }


    public <T> T getAttr(Object pKey, Class<T> type) {
        return type.cast(attrs.get(pKey));
    }


    public <T> T getAttr(Object pKey, Class<T> type, T defaultValue) {
        Object r = attrs.get(pKey);
        return (r == null ? defaultValue : type.cast(r));
    }


    public <T> T getValue(Class<T> type) {
        if (type == null) return null;
        Object r = attrs.get(type);
        if (r != null) type.cast(r);
        for (Map.Entry<Object, Object> e : attrs.entrySet()) {
            if (e.getKey() instanceof Class && type.isAssignableFrom((Class<?>) e.getKey())) return type.cast(e.getValue());
        }
        return null;
    }


    public Boolean getBoolean(Object pKey) {
        return getAttr(pKey, Boolean.class);
    }


    public Boolean getBoolean(Object pKey, Boolean defaultValue) {
        return getAttr(pKey, Boolean.class, defaultValue);
    }


    public Integer getInteger(Object pKey) {
        return getAttr(pKey, Integer.class);
    }


    public Integer getInteger(Object pKey, Integer defaultValue) {
        return getAttr(pKey, Integer.class, defaultValue);
    }


    public Long getLong(Object pKey) {
        return getAttr(pKey, Long.class);
    }


    public Long getLong(Object pKey, Long defaultValue) {
        return getAttr(pKey, Long.class, defaultValue);
    }


    public String getStr(Object pKey) {
        return getAttr(pKey, String.class);
    }


    public String getStr(Object pKey, String defaultValue) {
        return getAttr(pKey, String.class, defaultValue);
    }


    @Override
    public String toString() {
        return "Context [" + attrs + "]";
    }
}
