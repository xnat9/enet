package cn.xnatural.enet.event;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * event context: 事件执行的上下文
 */
public class EC {
    /**
     * 一次事件执行的id. 用于追踪执行的是哪次事件
     */
    String   id;
    /**
     * 是否追踪执行.用于调试
     */
    boolean  track;
    /**
     * 强制异步. 如果设置了就会忽略 @EL中的设置
     */
    Boolean  async;
    /**
     * 目标方法的参数
     */
    Object[] args;
    /**
     * 是由哪个事件发布器发布的
     */
    EP       ep;
    /**
     * 事件源
     */
    private   Object              source;
    /**
     * 用于临时存放, 上一个Listener的执行结果.
     * 更灵活的数据存储请用 {@link #attr(Object, Object)}
     */
    public    Object              result;
    /**
     * 用于临时存放, 上一个Listener的执行异常
     */
    public    Throwable           ex;
    /**
     * 要执行的事件链
     */
    protected List<EP.Listener>   willPass;
    /**
     * 执行过的事件链
     */
    protected List<EP.Listener>   passed = new LinkedList<>();
    protected Map<Object, Object> attrs  = new ConcurrentHashMap<>(7);


    public static EC of(Object source) {
        return new EC(source);
    }
    public static EC of(Object key, Object value) {
        return new EC().attr(key, value);
    }


    public EC() {}
    public EC(Object source) {
        this.source = source;
    }


    /**
     * 此条事件执行链的要执行的所有监听
     * @param ls
     * @return
     */
    EC willPass(List<EP.Listener> ls) {
        willPass = ls;
        return this;
    }


    /**
     * passed一个Listener 代表执行成功一个Listener. 即: 执行成功后调用
     * @param l
     * @return
     */
    EC passed(EP.Listener l) {
        passed.add(l);
        return this;
    }


    /**
     * 事件是否执行成功
     * @return
     */
    public boolean isSuccess() {
        return isNoListener() || willPass.size() == passed.size();
    }


    /**
     * 是否没有监听器
     * @return
     */
    public boolean isNoListener() {
        return willPass == null;
    }


    /**
     * 同步执行
     * @return
     */
    public EC sync() { async = false; return this; }


    /**
     * 异步执行
     * @return
     */
    public EC async() { async = true; return this; }


    public EC debug() { track = true; return this; }


    public EC args(Object... args) { this.args = args; return this; }


    public Object source() { return source; }


    public EC source(Object s) {
        if (willPass != null) throw new RuntimeException("not allow change event source!");
        this.source = s;
        return this;
    }


    public EC result(Object result) {
        this.result = result;
        return this;
    }


    public EP ep() { return ep; }


    public EC attr(Object key, Object value) {
        attrs.put(key, value);
        return this;
    }


    public <T> T getAttr(Object key, Class<T> type, T defaultValue) {
        return type.cast(attrs.getOrDefault(key, defaultValue));
    }


    public <T> T getAttr(Object key, Class<T> type) {
        return getAttr(key, type, null);
    }


    public Object getAttr(Object key) {
        return attrs.get(key);
    }
}
