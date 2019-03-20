package org.xnatural.enet.server.http.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerKeepAliveHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 用 netty 实现的 http server
 */
public class Netty4Http extends ServerTpl {
    /**
     * http 服务监听端口
     */
    protected int               port;
    /**
     * http 服务绑定地址
     */
    protected String            hostname;
    protected NioEventLoopGroup boosGroup;
    protected NioEventLoopGroup workerGroup;


    public Netty4Http() {
        setName("http-netty");
        setPort(8080);
        setHostname("localhost");
    }


    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        // 先从核心取配置, 然后再启动
        attrs.putAll((Map) coreEp.fire("env.ns", "http", getName()));
        port = getInteger("port", getPort());
        hostname = getStr("hostname", getHostname());
        createServer();
        coreEp.fire(getName() + ".started");
    }


    @Override
    public void stop() {
        log.info("Shutdown '{}' Server. hostname: {}, port: {}", getName(), getHostname(), getPort());
        if (boosGroup != null) boosGroup.shutdownGracefully();
        if (workerGroup != null && workerGroup != boosGroup) workerGroup.shutdownGracefully();
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    /**
     * 创建服务
     */
    protected void createServer() {
        boolean isLinux = isLinux();
        Boolean shareLoop = getBoolean("shareLoop", true);
        boosGroup = new NioEventLoopGroup(getInteger("threads-boos", 1), coreExec);
        workerGroup = shareLoop ? boosGroup : new NioEventLoopGroup(getInteger("threads-worker", 1), coreExec);
        ServerBootstrap sb = new ServerBootstrap()
                .group(boosGroup, workerGroup)
                .channel(isLinux ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new IdleStateHandler(2, 0, 0, TimeUnit.MINUTES));
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpServerKeepAliveHandler());
                        ch.pipeline().addLast(new HttpObjectAggregator(getInteger("maxContentLength", 65536)));
                        ch.pipeline().addLast(new ChunkedWriteHandler());
                        coreEp.fire("http-netty.addHandler", ec -> {
                            if (ec.isNoListener()) {
                                log.error("'{}' server not available handler", getName());
                                stop();
                            }
                        }, ch.pipeline());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, getInteger("backlog", 500))
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        try {
            sb.bind(getHostname(), getPort()).sync();
            log.info("Started {} Server. hostname: {}, port: {}", getName(), getHostname(), getPort());
        } catch (Exception ex) {
            log.error(ex);
        }
    }


    /**
     * 判断系统是否为 linux 系统
     * @return
     */
    protected boolean isLinux() {
        return (System.getProperty("os.name").toLowerCase(Locale.UK).trim().startsWith("linux"));
    }


    public String getHostname() {
        return hostname;
    }


    public Netty4Http setHostname(String hostname) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新主机名");
        attrs.put("hostname", hostname); this.hostname = hostname;
        return this;
    }


    public int getPort() {
        return port;
    }


    public Netty4Http setPort(int port) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新端口");
        attrs.put("port", port); this.port = port;
        return this;
    }
}
