package cn.xnatural.enet.event;

import cn.xnatural.enet.event.EP.Listener;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * event context: 事件执行的上下文
 */
public class EC {
    /**
     * 一次事件执行的id. 用于追踪执行的是哪次事件
     */
    protected String   id;
    /**
     * 是否追踪执行.用于调试
     */
    boolean  track;
    /**
     * 强制异步. 如果设置了就会忽略 @EL中的设置
     */
    protected Boolean  async;
    /**
     * 目标方法的参数
     */
    Object[] args;
    /**
     * 是由哪个事件发布器发布的
     */
    EP       ep;
    /**
     * 是否 暂停
     */
    protected boolean            pause;
    /**
     * 此次执行的事件名
     */
    protected String              eName;
    /**
     * 事件执行完成的回调函数
     */
    protected Consumer<EC>        completeFn;
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
    protected Throwable           ex;
    /**
     * 错误消息
     */
    protected String              errMsg;
    /**
     * 要执行的事件链
     */
    protected List<Listener>      willPass;
    /**
     * 成功执行过的事件监听
     */
    protected List<Listener>      successPassed = new LinkedList<>();
    /**
     * 执行失败的事件监听
     */
    protected List<Listener>      failPassed = new LinkedList<>();
    /**
     * 是否结束
     */
    protected AtomicBoolean       stopped       = new AtomicBoolean(false);
    /**
     * 属性集
     */
    protected Map<Object, Object> attrs         = new ConcurrentHashMap<>(7);


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
     * 开始执行,初始化
     * @param eName
     * @param ls
     * @param ep
     */
    void start(String eName, List<Listener> ls, EP ep) {
        this.eName = eName; willPass = ls; this.ep = ep;
        if (track) { // 是否要追踪此条事件链的执行
            id = UUID.randomUUID().toString();
            ep.log.info("Starting listener chain for event '{}'. id: {}, event source: {}", eName, id, source());
        }
    }


    /**
     * 此次事件执行完成
     */
    public void tryFinish() {
        if (stopped.get() || pause) return;
        boolean noL = isNoListener();
        if (noL && track) ep.log.info("Not found listener for event '{}'. id: {}", eName, id);
        if ((noL || successPassed.size() + failPassed.size() == willPass.size()) && stopped.compareAndSet(false, true)) { // 防止并发时被执行多遍
            boolean f = isSuccess();
            if (f && track) ep.log.info("End listener chain for event '{}'. id: {}, result: {}", eName, id, result);
            else if (!f) ep.log.warn("End listener chain for event '{}'. id: {}, result: {}, failDesc: {}", eName, id, result, failDesc());
            Consumer<EC> fn = completeFn();
            if (fn != null) fn.accept(this);
        }
    }



    /**
     * passed一个Listener 代表执行完成一个Listener.
     * @param l {@link Listener}
     * @param success 成功执行
     * @return
     */
    EC passed(Listener l, boolean success) {
        if (success) successPassed.add(l);
        else failPassed.add(l);
        return this;
    }


    /**
     * 事件是否执行成功
     * @return
     */
    public boolean isSuccess() {
        return isNoListener() || (willPass.size() == successPassed.size() && ex == null && errMsg == null && pause == false);
    }


    /**
     * 是否没有监听器
     * @return
     */
    public boolean isNoListener() {
        return willPass == null || willPass.isEmpty();
    }


    /**
     * 设置完成时回调函数
     * @param completeFn
     * @return
     */
    public EC completeFn(Consumer<EC> completeFn) {
        this.completeFn = completeFn;
        return this;
    }


    public Consumer<EC> completeFn() {
        return completeFn;
    }


    /**
     * 挂起 和 {@link #resume()} 配套执行
     * @return
     */
    public EC suspend() { this.pause = true; return this; }


    /**
     * 恢复 和 {@link #suspend()} 配套执行
     * @return
     */
    public EC resume() {this.pause = false; return this;}


    /**
     * 强制同步执行
     * @return
     */
    public EC sync() { return async(false); }


    public EC async(Boolean async) { this.async = async; return this; }


    /**
     * 是否为强制异步执行
     * @return
     */
    public Boolean isForceAsync() {return this.async;}


    public EC debug() { track = true; return this; }


    public EC args(Object... args) { this.args = args; return this; }


    /**
     * 设置id
     * @param id
     * @return
     */
    public EC id(String id) {this.id = id; return this;}


    public String id() {return this.id;}


    /**
     * 事件名 {@link #eName}
     * @return
     */
    public String eName() {return this.eName;}


    public EC ex(Throwable t) {this.ex = t; return this;}


    public Throwable ex() { return ex; }


    public EC errMsg(String msg) {this.errMsg = msg; return this;}


    /**
     * 错误消息
     * @return
     */
    public String errMsg() { return errMsg; }


    /**
     * 失败描述
     * @return
     */
    public String failDesc() {
        return (ex() == null ? errMsg() : (ex().getMessage() == null ? ex().getClass().getName() : ex().getMessage()));
    }


    public Object source() { return source; }


    public EC source(Object s) {
        if (eName != null) throw new RuntimeException("not allow change event source!");
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
