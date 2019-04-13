package cn.xnatural.enet.server.mview;

import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * mview web界面管理模块
 * 对系统中的所有模块管理
 */
public class MViewServer extends ServerTpl {
    protected final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * mview http path前缀. 默认为: mview
     */
    protected   String        path;
    protected   Controller    ctl;

    public MViewServer() {
        super("mview");
    }
    public MViewServer(String name) { super(name); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (ep == null) ep = new EP();
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
