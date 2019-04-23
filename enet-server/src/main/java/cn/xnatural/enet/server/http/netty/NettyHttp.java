package cn.xnatural.enet.server.http.netty;

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
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;

import javax.annotation.Resource;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static cn.xnatural.enet.common.Utils.isEmpty;

/**
 * 用 netty 实现的 http server
 */
public class NettyHttp extends ServerTpl {
    protected final AtomicBoolean  running = new AtomicBoolean(false);
    @Resource
    protected       Executor       exec;
    protected       EventLoopGroup boosGroup;
    protected       EventLoopGroup workerGroup;


    public NettyHttp() { this("http-netty"); }
    public NettyHttp(String name) { super(name); setPort(8080);}


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (exec == null) exec = Executors.newFixedThreadPool(2);
        if (ep == null) ep = new EP(exec);
        ep.fire(getName() + ".starting");
        attrs.putAll((Map) ep.fire("env.ns", "http", getName()));
        createServer();
        ep.fire(getName() + ".started");
    }


    /**
     * async 为false是为了保证 此服务最先被关闭.(先断掉新的请求, 再关闭其它服务)
     */
    @EL(name = "sys.stopping", async = false)
    public void stop() {
        log.info("Shutdown '{}' Server. hostname: {}, port: {}", getName(), isEmpty(getHostname()) ? "0.0.0.0" : getHostname(), getPort());
        if (boosGroup != null) boosGroup.shutdownGracefully();
        if (workerGroup != null && workerGroup != boosGroup) workerGroup.shutdownGracefully();
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    /**
     * 当前正在处理的连接个数
     */
    protected AtomicInteger connCount = new AtomicInteger(0);
    /**
     * 创建http服务
     */
    protected void createServer() {
        boolean useEpoll = isLinux() && getBoolean("epollEnabled", true);
        boosGroup = useEpoll ? new EpollEventLoopGroup(getInteger("threads-boos", 1), exec) : new NioEventLoopGroup(getInteger("threads-boos", 1), exec);
        workerGroup = getBoolean("shareLoop", true) ? boosGroup : (useEpoll ? new EpollEventLoopGroup(getInteger("threads-worker", 1)) : new NioEventLoopGroup(getInteger("threads-worker", 1), exec));
        ServerBootstrap sb = new ServerBootstrap()
                .group(boosGroup, workerGroup)
                .channel(useEpoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new IdleStateHandler(getLong("readerIdleTime", 2 * 60L), getLong("writerIdleTime", 0L), getLong("allIdleTime", 0L), TimeUnit.SECONDS));
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<Object>(false) {
                            @Override
                            public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                                connCount.incrementAndGet(); super.channelRegistered(ctx);
                            }
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (!fusing(ctx, msg)) ctx.fireChannelRead(msg);
                            }
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                connCount.decrementAndGet(); super.channelInactive(ctx);
                            }
                        });
                        ch.pipeline().addLast(new HttpServerKeepAliveHandler());
                        ch.pipeline().addLast(new HttpObjectAggregator(getInteger("maxContentLength", 65536)));
                        ch.pipeline().addLast(new ChunkedWriteHandler());
                        ep.fire("http-netty.addHandler", ec -> {
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


    /**
     * 熔断: 是否拒绝处理请求
     * @param ctx
     * @param msg
     * @return
     */
    protected boolean fusing(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DefaultHttpRequest)) return false;
        if (connCount.get() > getInteger("maxConnection", 200)) { // 最大连接
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
