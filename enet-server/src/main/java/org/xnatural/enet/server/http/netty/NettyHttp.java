package org.xnatural.enet.server.http.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.xnatural.enet.common.Utils.isEmpty;

/**
 * 用 netty 实现的 http server
 */
public class NettyHttp extends ServerTpl {
    protected EventLoopGroup boosGroup;
    protected EventLoopGroup workerGroup;


    public NettyHttp() {
        setName("http-netty");
        setPort(8080);
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", "http", getName()));
        createServer();
        coreEp.fire(getName() + ".started");
    }


    /**
     * async 为false是为了保证 此服务最先被关闭.(先断掉新的请求, 再关闭其它服务)
     */
    @EL(name = "sys.stopping", async = false)
    public void stop() {
        log.info("Shutdown '{}' Server. hostname: {}, port: {}", getName(), isEmpty(getHostname()) ? "0.0.0.0" : getHostname(), getPort());
        if (boosGroup != null) boosGroup.shutdownGracefully();
        if (workerGroup != null && workerGroup != boosGroup) workerGroup.shutdownGracefully();
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    /**
     * 创建http服务
     */
    protected void createServer() {
        boolean useEpoll = isLinux() && getBoolean("epollEnabled", true);
        boosGroup = useEpoll ? new EpollEventLoopGroup(getInteger("threads-boos", 1), coreExec) : new NioEventLoopGroup(getInteger("threads-boos", 1), coreExec);
        workerGroup = getBoolean("shareLoop", true) ? boosGroup : (useEpoll ? new EpollEventLoopGroup(getInteger("threads-worker", 1)) : new NioEventLoopGroup(getInteger("threads-worker", 1), coreExec));
        ServerBootstrap sb = new ServerBootstrap()
                .group(boosGroup, workerGroup)
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new IdleStateHandler(getLong("readerIdleTime", 2 * 60L), getLong("writerIdleTime", 0L), getLong("allIdleTime", 0L), TimeUnit.SECONDS));
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (!fusing(ctx, msg)) ctx.fireChannelRead(msg);
                            }
                        });
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
                .option(ChannelOption.SO_BACKLOG, getInteger("backlog", 100))
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        try {
            if (isEmpty(getHostname())) sb.bind(getPort()).sync(); // 默认绑定本地所有地址
            else sb.bind(getHostname(), getPort()).sync();
            log.info("Started {} Server. hostname: {}, port: {}, type: {}", getName(), isEmpty(getHostname()) ? "0.0.0.0" : getHostname(), getPort(), (useEpoll ? "epoll" : "nio"));
        } catch (Exception ex) {
            log.error(ex);
        }
    }


    protected int down = 0;
    /**
     * 监听系统负载
     * @param down
     */
    @EL(name = "sys.load", async = false)
    protected void sysLoad(Integer down) { this.down = down; }


    /**
     * 熔断: 是否拒绝处理请求
     * @param ctx
     * @param msg
     * @return
     */
    protected boolean fusing(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DefaultHttpRequest)) return false;
        if (down > 0) { // 当系统负载过高时拒绝处理
            down--;
            DefaultHttpResponse resp = new DefaultHttpResponse(((DefaultHttpRequest) msg).protocolVersion(), SERVICE_UNAVAILABLE);
            ctx.writeAndFlush(resp); ctx.close();
            return true;
        }
        return false;
    }


    /**
     * 判断系统是否为 linux 系统
     * @return
     */
    protected boolean isLinux() {
        return System.getProperty("os.name").toLowerCase(Locale.UK).trim().startsWith("linux");
    }


    @EL(name = "http.getHostname", async = false)
    public String getHostname() {
        return getStr("hostname", "");
    }


    @EL(name = "http.getPort", async = false)
    public int getPort() {
        return getInteger("port", 8080);
    }


    public NettyHttp setPort(int port) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新端口");
        attr("port", port);
        return this;
    }
}
