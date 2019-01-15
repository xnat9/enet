package org.xnatural.enet.modules.mview;

import org.xnatural.enet.core.ServerTpl;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.util.Map;

/**
 * mview web界面管理模块
 * 对系统中的所有模块管理
 */
public class MViewServer extends ServerTpl {

    private Controller ctl;

    public MViewServer() {
        setName("mview");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务({})正在运行", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getNs() + ".starting");
        // 先从核心取配置, 然后再启动
        coreEp.fire("sys.env.ns", EC.of("ns", getNs()), (ec) -> {
            if (ec.result != null) {
                Map<String, Object> m = (Map) ec.result;
                attrs.putAll(m);
            }
            ctl = new Controller();
            log.info("创建({})服务.", getName());
            coreEp.fire("server.undertowRestEasy.addResource", EC.of("source", ctl));
        });
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("关闭({})服务.", getName());
        // TODO
    }
}
