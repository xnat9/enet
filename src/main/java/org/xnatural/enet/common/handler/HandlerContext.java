package org.xnatural.enet.common.handler;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Created by xxb on 2017/9/28.
 */
public class HandlerContext {
    /**
     * 处理过程中添加的属性
     */
    private Map<Object, Object> attrs = new HashMap<>();
    /**
     * 当前处理的 Handler
     */
    private HandlerChain        handler;
    /**
     * 处理的结果
     */
    private Object              result;
    /**
     * 当前处理的 Processor
     */
    private Processor           currentProcessor;


    public Object getAttr(Object key) {
        return attrs.get(key);
    }


    public <T> T getAttr(Object key, Class<T> cls) {
        return cls.cast(attrs.get(key));
    }


    public String getAttrString(Object key) {
        return getAttr(key, String.class);
    }


    public Boolean getAttrBoolean(Object key) {
        return getAttr(key, Boolean.class);
    }


    public Integer getAttrInteger(Object key) {
        return getAttr(key, Integer.class);
    }


    public Long getAttrLong(Object key) {
        return getAttr(key, Long.class);
    }


    public HandlerContext attr(Object key, Object value) {
        attrs.put(key, value);
        return this;
    }


    public HandlerContext addAttrs(Map<Object, Object> attrs) {
        this.attrs.putAll(attrs);
        return this;
    }


    public HandlerContext addResult(Object key, Object value) {
        if (result == null) result = new LinkedHashMap<>();
        ((Map) result).put(key, value);
        return this;
    }


    public Object getResult(Object key) {
        if (result == null) return null;
        else if (result instanceof Map) {
            return ((Map) result).get(key);
        } else {
            throw new IllegalArgumentException("result is a not map data structure");
        }
    }


    public <T> T getResult(Object key, Class<T> cls) {
        return cls.cast(getResult(key));
    }


    public Object getResult() {
        return result;
    }


    public void setResult(Object result) {
        this.result = result;
    }


    public HandlerChain getHandler() {
        return handler;
    }


    public void setHandler(HandlerChain handler) {
        this.handler = handler;
    }


    public Processor currentProcessor() {
        return currentProcessor;
    }


    public void currentProcessor(Processor currentProcessor) {
        this.currentProcessor = currentProcessor;
    }
}
