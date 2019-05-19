package cn.xnatural.enet.server.remote;

import cn.xnatural.enet.common.Utils;
import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.xnatural.enet.common.Utils.isEmpty;

/**
 * @author xiangxb, 2019-05-18
 */
public class Remoter extends ServerTpl {
    protected final AtomicBoolean  running = new AtomicBoolean(false);
    @Resource
    protected       Executor       exec;
    /**
     * 暴露的远程调用端口
     */
    protected       Integer        exposePort;
    protected       EventLoopGroup boosGroup;
    protected       EventLoopGroup workerGroup;


    public Remoter() { super("remote"); }
    public Remoter(String name) { super(name); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (ep == null) ep = new EP();
        ep.fire(getName() + ".starting");
        attrs.putAll((Map) ep.fire("env.ns", getName()));
        exposePort = getInteger("exposePort", null);

        ep.fire(getName() + ".started");
        log.info("Started {} Server.", getName());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        if (boosGroup != null) boosGroup.shutdownGracefully();
        if (workerGroup != null && workerGroup != boosGroup) workerGroup.shutdownGracefully();
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    @EL(name = "remote")
    protected Object invoke(EC ec, String appName, String eName, Object[] remoteMethodArgs) {
        ec.suspend();
        JSONObject params = new JSONObject(4);
        if (isEmpty(ec.id())) ec.id(UUID.randomUUID().toString());
        params.put("eId", ec.id());
        params.put("async", ec.isAsync());
        params.put("eName", eName);
        if (remoteMethodArgs != null) {
            JSONArray args = new JSONArray(remoteMethodArgs.length);
            params.put("args", args);
            for (Object arg : remoteMethodArgs) {
                args.add(new JSONObject(2).fluentPut("type", arg.getClass().getName()).fluentPut("value", arg));
            }
        }
        // new Bootstrap()
        return null;
    }


    /**
     * 当前连接数
     */
    protected AtomicInteger connCount = new AtomicInteger(0);
    /**
     * 创建监听
     */
    protected void createServer() {
        if (exposePort == null) return;
        String loopType = getStr("loopType", (isLinux() ? "epoll" : "nio"));
        Boolean shareLoop = getBoolean("shareLoop", true);
        Class ch = null;
        if ("epoll".equalsIgnoreCase(loopType)) {
            boosGroup = new EpollEventLoopGroup(getInteger("threads-boos", 1), exec);
            workerGroup = (shareLoop ? boosGroup : new EpollEventLoopGroup(getInteger("threads-worker", 1), exec));
            ch = EpollServerSocketChannel.class;
        } else if ("nio".equalsIgnoreCase(loopType)) {
            boosGroup = new NioEventLoopGroup(getInteger("threads-boos", 1), exec);
            workerGroup = (shareLoop ? boosGroup : new NioEventLoopGroup(getInteger("threads-worker", 1), exec));
            ch = NioServerSocketChannel.class;
        }
        ServerBootstrap sb = new ServerBootstrap()
            .group(boosGroup, workerGroup)
            .channel(ch)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                            if (!fusing(ctx)) {
                                connCount.incrementAndGet();
                                log.debug("Connection registered: {}", connCount);
                                super.channelRegistered(ctx);
                            }
                        }
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
                            super.channelUnregistered(ctx); connCount.decrementAndGet();
                            log.debug("Connection unregistered: {}", connCount);
                        }
                    });
                    ch.pipeline().addLast(new IdleStateHandler(getLong("readerIdleTime", 2 * 60L), getLong("writerIdleTime", 0L), getLong("allIdleTime", 0L), TimeUnit.SECONDS));
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            ByteBuf buf = (ByteBuf) msg;
                            try {
                                if (buf.readableBytes() <= 0) return;
                                byte[] bs = new byte[buf.readableBytes()];
                                buf.readBytes(bs);
                                receiveEvent(ctx, JSON.parseObject(new String(bs)));
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
            String host  = getStr("hostname", null);
            if (Utils.isNotEmpty(host)) sb.bind(host, exposePort).sync(); // 如果没有配置hostname, 默认绑定本地所有地址
            else sb.bind(exposePort).sync();
            log.info(
                "Started {} Server. hostname: {}, port: {}, type: {}, shareEventLoop: {}",
                getName(), (Utils.isNotEmpty(host) ? host : "0.0.0.0"), exposePort, loopType, shareLoop
            );
        } catch (Exception ex) {
            log.error(ex);
        }
    }


    /**
     * 接收远程事件调用
     * @param ctx
     * @param data
     */
    protected void receiveEvent(ChannelHandlerContext ctx, JSONObject data) {
        EC ec = new EC();
        ec.id(data.getString("eId"));
        ec.async(Boolean.TRUE.equals(data.getBoolean("async")));
        ec.args(data.getJSONArray("args") == null ? null : data.getJSONArray("args").stream().map(o -> {
            JSONObject jo = (JSONObject) o;
            String t = jo.getString("type");
            if (String.class.getName().equals(t)) return jo.getString("value");
            else if (Boolean.class.getName().equals(t)) return jo.getBoolean("value");
            else if (Integer.class.getName().equals(t)) return jo.getInteger("value");
            else if (Short.class.getName().equals(t)) return jo.getShort("value");
            else if (Long.class.getName().equals(t)) return jo.getLong("value");
            else if (Double.class.getName().equals(t)) return jo.getDouble("value");
            else if (Float.class.getName().equals(t)) return jo.getFloat("value");
            else if (BigDecimal.class.getName().equals(t)) return jo.getBigDecimal("value");
            else if (JSONObject.class.getName().equals(t)) return jo.getJSONObject("value");
            else throw new IllegalArgumentException("Not support parameter type '" + t + "'");
        }).toArray());
        ep.fire(data.getString("eName"), ec);
        if (!ec.isAsync()) { // 远程的异步事件不响应
            JSONObject r = new JSONObject();
            r.put("success", ec.isSuccess());
            r.put("result", ec.result);
            r.put("eId", ec.id());
            ctx.writeAndFlush(r.toJSONString());
        }
    }


    /**
     * 熔断: 是否拒绝处理请求
     * @param ctx
     * @return
     */
    protected boolean fusing(ChannelHandlerContext ctx) {
        if (connCount.get() >= getInteger("maxConnection", 200)) { // 最大连接
            ctx.writeAndFlush(new JSONObject().fluentPut("msg", "xxx").toJSONString());
            ctx.close();
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
}
