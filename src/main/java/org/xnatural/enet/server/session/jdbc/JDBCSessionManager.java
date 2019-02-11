package org.xnatural.enet.server.session.jdbc;

import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Map;

/**
 * @author xiangxb, 2019-02-05
 */
public class JDBCSessionManager extends ServerTpl {

    public JDBCSessionManager() {
        setName("jdbcSession");
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
        coreEp.fire(getNs() + ".started");
        log.info("Started {} Server", getName());
    }


    @Override
    public void stop() {

    }
}
