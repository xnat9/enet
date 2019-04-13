package cn.xnatural.enet.server.session;

import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author xiangxb, 2019-02-05
 */
public class MemSessionManager extends ServerTpl {
    protected final AtomicBoolean            running = new AtomicBoolean(false);
    /**
     * 过期时间(单位: 分钟)
     */
    protected       Integer                  expire;
    protected       Map<String, SessionData> sMap;

    public MemSessionManager() {
        super("session-mem");
    }
    public MemSessionManager(String name) { super(name); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (ep == null) ep = new EP();
        ep.fire(getName() + ".starting");
        attrs.putAll((Map) ep.fire("env.ns", "session", getName()));
        expire = getInteger("expire", 30);
        sMap = new ConcurrentHashMap<>();
        ep.fire(getName() + ".started");
        log.info("Started {} Server", getName());
    }


    @EL(name = {"${name}.set", "session.set"})
    protected void set(String sId, Object key, Object value) {
        createOrGet(sId).data.put(key, value);
    }


    /**
     * access session
     * @param sId
     */
    @EL(name = {"${name}.access", "session.access"}, async = false)
    protected void access(String sId) {
        createOrGet(sId).accessTime = System.currentTimeMillis();
    }


    /**
     * create session
     * @param sId
     * @return {@link SessionData}
     */
    protected SessionData createOrGet(String sId) {
        if (sMap.containsKey(sId)) {
            SessionData d = sMap.get(sId);
            // 判断是否已过期
            if ((System.currentTimeMillis() - d.accessTime) > TimeUnit.MINUTES.toMillis(expire)) sMap.remove(sId);
            else return d;
        }
        synchronized(this) {
            if (sMap.containsKey(sId)) return sMap.get(sId);
            SessionData d = new SessionData(); sMap.put(sId, d);
            d.data = new ConcurrentHashMap<>();
            d.accessTime = System.currentTimeMillis();
            log.info("new session '{}' at {}", sId, d.accessTime);
            return d;
        }
    }


    @EL(name = {"${name}.get", "session.get"}, async = false)
    protected Object get(String sId, Object key) {
        return createOrGet(sId).data.get(key);
    }


    @EL(name = "session.getExpire", async = false)
    public Integer getExpire() {
        return expire;
    }


    public MemSessionManager setExpire(Integer expire) {
        if (expire == null) throw new NullPointerException("参数为空");
        this.expire = expire;
        attr("expire", expire);
        return this;
    }


    protected class SessionData {
        Long accessTime; Map<Object, Object> data;
    }
}
