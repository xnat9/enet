package org.xnatural.enet.server.session;

import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author xiangxb, 2019-02-05
 */
public class MemSessionManager extends ServerTpl {

    protected Map<String, Object> sMap;

    public MemSessionManager() {
        setName("memSession");
    }


    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getNs() + ".starting");
        // 先从核心取配置, 然后再启动
        Map<String, String> r = (Map) coreEp.fire("env.ns", getNs());
        attrs.putAll(r);
        sMap = new ConcurrentHashMap<>();
        coreEp.fire(getNs() + ".started");
        log.info("Started {} Server", getName());
    }


    @EL(name = {"${ns}.session.put", "session.put"})
    protected MemSessionManager put(String sId, Object data) {
        sMap.put(sId, data);
        return this;
    }


    @Override
    public void stop() {

    }
}
