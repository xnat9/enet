package org.xnatural.enet.server.http.custome;

import org.xnatural.enet.common.Utils;
import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * apache, netty, tomcat, undertow
 * http 服务
 *
 * @author xiangxb, 2018-12-14
 */
public class HttpServer extends ServerTpl {
    /**
     * http 服务监听端口
     */
    private int                 port;
    /**
     * http 服务绑定地址
     */
    private String              hostname;

    /**
     * NIO 的 IO 响应器.(监听socket连接事件变化)
     */
    private IOReactor           ioReactor;


    public HttpServer() {
        setName("http");
        setPort(8080);
        setHostname("localhost");
    }
    /**
     * 服务名标识
     * @param name
     */
    public HttpServer(String name) {
        setName(name);
        setPort(8080);
        setHostname("localhost");
    }


    @EL(name = "sys.starting")
    public final void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("服务正在运行"); return;
        }
        log.debug("创建http服务. hostname: {}, port: {}", getHostname(), getPort());
        if (coreEp == null) coreEp = new EP();
        coreEp.fire(getNs() + ".starting", EC.of(this));
        // 先从核心取配置, 然后再启动
        coreEp.fire("env.ns", EC.of(this).attr("ns", getNs()), (ec) -> {
            attrs.putAll((Map) ec.result);
            try {
                if (coreExec == null) initExecutor();
                ioReactor = new IOReactor(this, coreExec);
                ioReactor.start();
                log.info("http服务启动完成. hostname: {}, port: {}, attrNs: {}", getHostname(), getPort());
                coreEp.fire(getNs() + ".started", EC.of(this));
            } catch (Exception ex) {
                log.error(ex, "创建http服务错误");
            }
        });
    }


    @EL(name = "sys.stopping")
    public final void stop() {
        if (!running.compareAndSet(true, false)) {
            log.warn("服务已关闭"); return;
        }
        log.info("关闭http服务. hostname:{}, port: {}", hostname, port);
        coreEp.fire(getNs() + ".stopping", EC.of(this));
        try {
            ioReactor.stop();
        } catch (IOException ex) {
            log.error(ex, "IOReactor关闭错误");
        }
        // TODO 关闭所有现在正在处理的请求?
        if (coreExec instanceof ExecutorService) {
            ((ExecutorService) coreExec).shutdown();
            coreExec = null;
        }
        coreEp.fire(getNs() + ".stopped", EC.of(this));
    }


    @EL(name = "env.updateAttr")
    private void updateAttr(EC ec) {
        String k = ec.getAttr("key", String.class);
        if (!k.startsWith(getNs())) return;

        String v = ec.getAttr("value", String.class);
        if (Objects.equals(getNs() + ".port",  k)) setPort(Utils.toInteger(v, getPort()));
        else if (Objects.equals(getNs() + ".hostname",  k)) setHostname(v);
            // TODO 添加其它可在运行时更新的属性
        else new RuntimeException("不允许更新属性: " + k);
    }


    public static void main(String[] args) {
        // new HttpServer().setPort(8081).start();
    }



    void toRequest(SelectionKey key) {
        try {
            // 1. 解析request.
            HttpRequest req = new HttpRequest(key);
            // 2. dispatch request
            // if (dispatcher != null) dispatcher.dispatch(req);
            // 3. 响应
            ((SocketChannel) key.channel()).write(ByteBuffer.wrap("11111111111".getBytes()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    void toRequest(Socket s) {
    }


    public String getHostname() {
        return hostname;
    }


    public HttpServer setHostname(String hostname) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新主机名");
        attrs.put("hostname", hostname); this.hostname = hostname;
        return this;
    }


    public int getPort() {
        return port;
    }


    public HttpServer setPort(int port) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新端口");
        attrs.put("port", port); this.port = port;
        return this;
    }
}
