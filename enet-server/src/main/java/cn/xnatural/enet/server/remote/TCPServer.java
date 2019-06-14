package cn.xnatural.enet.server.remote;

import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;

import javax.annotation.Resource;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.xnatural.enet.common.Utils.isEmpty;
import static cn.xnatural.enet.common.Utils.isNotEmpty;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * tcp server
 */
class TCPServer extends ServerTpl {

    protected final Remoter        remoter;
    @Resource
    protected       Executor       exec;
    protected       Integer        port;
    protected       String         sysName;
    protected       EventLoopGroup boos;
    /**
     * 当前连接数
     */
    protected       AtomicInteger  connCount = new AtomicInteger(0);


    public TCPServer(Remoter remoter, EP ep, Executor exec) {
        super("tcp-server");
        this.ep = ep; this.exec = exec; this.remoter = remoter;
    }
    
    
    public void start() {
        attrs.putAll((Map) ep.fire("env.ns", getName()));
        port = getInteger("port", null);
        sysName = (String) ep.fire("sysName");
        create();
    }


    public void stop() {
        log.info("Close '{}'", getName());
        if (boos != null) boos.shutdownGracefully();
        connCount.set(0);
    }


    /**
     * 创建监听
     */
    protected void create() {
        if (port == null) {
            log.warn("'{}' need property 'port'", getName()); return;
        }
        if (isEmpty(sysName)) {
            log.warn("'{}' need property 'sysName'", getName()); return;
        }
        String loopType = getStr("loopType", (remoter.isLinux() ? "epoll" : "nio"));
        Class ch = null;
        if ("epoll".equalsIgnoreCase(loopType)) {
            boos = new EpollEventLoopGroup(getInteger("threads-boos", 1), exec);
            ch = EpollServerSocketChannel.class;
        } else if ("nio".equalsIgnoreCase(loopType)) {
            boos = new NioEventLoopGroup(getInteger("threads-boos", 1), exec);
            ch = NioServerSocketChannel.class;
        }
        ServerBootstrap sb = new ServerBootstrap()
            .group(boos)
            .channel(ch)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                            if (!fusing(ctx)) {
                                connCount.incrementAndGet();
                                log.debug("TCP Connection registered: {}", connCount);
                                super.channelRegistered(ctx);
                            }
                        }
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
                            super.channelUnregistered(ctx); connCount.decrementAndGet();
                            log.debug("TCP Connection unregistered: {}, addr: '{}'-'{}'", connCount, ch.remoteAddress(), ch.localAddress());
                        }
                    });
                    // 最好是将IdleStateHandler放在入站的开头，并且重写userEventTriggered这个方法的handler必须在其后面。否则无法触发这个事件。
                    ch.pipeline().addLast(new IdleStateHandler(getLong("readerIdleTime", 10 * 60L), getLong("writerIdleTime", 0L), getLong("allIdleTime", 0L), SECONDS));
                    ch.pipeline().addLast(new DelimiterBasedFrameDecoder(remoter.getInteger("maxFrameLength", 1024 * 1024), remoter.delimiter));
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                            log.error(cause, "server side error");
                        }
                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                            if (evt instanceof IdleStateEvent) { ctx.close(); }
                            else super.userEventTriggered(ctx, evt);
                        }
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            ByteBuf buf = (ByteBuf) msg;
                            String str = null;
                            try {
                                str = buf.toString(Charset.forName("utf-8"));
                                handleReceive(ctx, str);
                            } catch (JSONException ex) {
                                log.error("Received Error Data from '{}'. data: {}, errMsg: {}", ctx.channel().remoteAddress(), str, ex.getMessage());
                                ctx.close();
                            } catch (Exception ex) {
                                ctx.close(); log.error(ex);
                            } finally {
                                ReferenceCountUtil.release(msg);
                            }
                        }
                    });
                }
            })
            .option(ChannelOption.SO_BACKLOG, getInteger("backlog", 100))
            .childOption(ChannelOption.SO_KEEPALIVE, true);
        try {
            String host = getStr("hostname", null);
            if (isNotEmpty(host)) sb.bind(host, port).sync(); // 如果没有配置hostname, 默认绑定本地所有地址
            else sb.bind(port).sync();
            log.info("Start listen TCP {}:{}, type: {}", (isNotEmpty(host) ? host : "0.0.0.0"), port, loopType);
        } catch (Exception ex) {
            log.error(ex);
        }
    }


    /**
     * 处理接收来自己客户端的数据
     * @param ctx
     * @param data
     */
    protected void handleReceive(ChannelHandlerContext ctx, String data) {
        log.debug("Receive client '{}' data: {}", ctx.channel().remoteAddress(), data);

        JSONObject jo = JSON.parseObject(data);
        String from = jo.getString("source");
        if (isEmpty(from)) throw new IllegalArgumentException("Unknown source");
        else if (Objects.equals(sysName, from)) log.warn("Invoke self. appName", from);

        String t = jo.getString("type");
        if ("event".equals(t)) {
            exec.execute(() -> remoter.receiveEventReq(jo.getJSONObject("data"), o -> ctx.writeAndFlush(remoter.toByteBuf(o))));
        } else if ("heartbeat".equals(t)) { // 用来验证此条连接是否还可用
            remoter.receiveHeartbeat(jo.getJSONObject("data"), o -> ctx.writeAndFlush(remoter.toByteBuf(o)));
        } else throw new IllegalArgumentException("Not support exchange data type '" + t +"'");
    }


    /**
     * 熔断: 是否拒绝处理请求
     * @param ctx
     * @return
     */
    protected boolean fusing(ChannelHandlerContext ctx) {
        if (connCount.get() >= getInteger("maxConnection", 100)) { // 最大连接
            ctx.writeAndFlush(remoter.toByteBuf("server is busy"));
            ctx.close();
            return true;
        }
        return false;
    }
}
