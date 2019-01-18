package org.xnatural.enet.server.mview;

import org.xnatural.enet.core.ServerTpl;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * mview web界面管理模块
 * 对系统中的所有模块管理
 */
public class MViewServer extends ServerTpl {

    /**
     * mview http path前缀. 默认为: mview
     */
    private String path = "mview";
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
        coreEp.fire("sys.env.ns", EC.of("ns", getNs()).sync(), (ec) -> {
            if (ec.result != null) {
                Map<String, Object> m = (Map) ec.result;
                if (m.containsKey("path")) path = m.get("path").toString();
                attrs.putAll(m);
            }
            ctl = new Controller(this);
            log.info("创建({})服务.", getName());
            coreEp.fire("server.netty4Resteasy.addResource", EC.of("source", ctl).attr("path", getPath()));
        });
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("关闭({})服务.", getName());
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
        // TODO
    }


    public String getPath() {
        return path;
    }
}
