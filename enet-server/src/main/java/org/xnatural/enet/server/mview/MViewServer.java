package org.xnatural.enet.server.mview;

import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Map;

/**
 * mview web界面管理模块
 * 对系统中的所有模块管理
 */
public class MViewServer extends ServerTpl {

    /**
     * mview http path前缀. 默认为: mview
     */
    protected String path;
    protected Controller ctl;

    public MViewServer() {
        setName("mview");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (exec == null) initExecutor();
        if (ep == null) ep = new EP(exec);
        ep.fire(getName() + ".starting");
        attrs.putAll((Map) ep.fire("env.ns", getName()));
        path = getStr("path", "mview");

        ctl = new Controller(this);
        log.info("Started {} Server. pathPrefix: {}", getName(), ("/" + getPath() + "/").replace("//", "/"));
        ep.fire("resteasy.addResource", ctl, getPath());
    }


    public String getPath() {
        return path;
    }
}
