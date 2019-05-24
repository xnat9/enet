package cn.xnatural.enet.server.remote;

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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.xnatural.enet.common.Utils.*;

/**
 * @author xiangxb, 2019-05-18
 */
public class Remoter extends ServerTpl {
    protected final AtomicBoolean              running   = new AtomicBoolean(false);
    @Resource
    protected       Executor                   exec;
    /**
     * 暴露的远程调用端口
     */
    protected       Integer                    exposePort;
    protected       EventLoopGroup             clientBoosGroup;
    protected       Bootstrap                  boot;
    protected       EventLoopGroup             serverBoos;
    protected       EventLoopGroup             serverWorker;
    /**
     * 当前连接数
     */
    protected       AtomicInteger              connCount = new AtomicInteger(0);
    /**
     * ecId -> EC
     */
    protected       Map<String, EC>            ecMap;
    /**
     * appName -> list Channel
     */
    protected       Map<String, List<Channel>> appNameChannelMap;
    /**
     * host:port -> list Channel
     */
    protected       Map<String, List<Channel>> hostChannelMap;
    /**
     * 系统名字(标识)
     */
    protected       String                     sysName;


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
        sysName = (String) ep.fire("sysName");
        exposePort = getInteger("exposePort", null);

        createServer();

        ep.fire(getName() + ".started");
        log.info("Started {} Server.", getName());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        if (serverBoos != null) serverBoos.shutdownGracefully();
        if (serverWorker != null && serverWorker != serverBoos) serverWorker.shutdownGracefully();
        if (clientBoosGroup != null) clientBoosGroup.shutdownGracefully();
        boot = null;
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    @EL(name = "remote")
    protected void invoke(EC ec, String appName, String eName, Object[] remoteMethodArgs) throws Exception {
        ec.suspend();
        try {
            JSONObject params = new JSONObject(4);
            if (isEmpty(ec.id())) ec.id(UUID.randomUUID().toString());
            params.put("eId", ec.id());
            params.put("reply", ec.completeFn() != null); // 是否需要远程响应执行结果(有完成回调函数就需要远程响应调用结果)
            params.put("async", ec.isAsync());
            params.put("eName", eName);
            if (remoteMethodArgs != null) {
                JSONArray args = new JSONArray(remoteMethodArgs.length);
                params.put("args", args);
                for (Object arg : remoteMethodArgs) {
                    args.add(new JSONObject(2).fluentPut("type", arg.getClass().getName()).fluentPut("value", arg));
                }
            }
            ecMap.put(ec.id(), ec);
            // 发送请求给远程应用appName执行
            channel(appName).writeAndFlush(new JSONObject(2).fluentPut("type", "event").fluentPut("data", params).toJSONString());
        } catch (Throwable ex) { ec.resume(); throw ex; }
    }


    /**
     * 得到一个指向 appName 的Channel
     * @param appName
     * @return
     * @throws Exception
     */
    protected Channel channel(String appName) {
        if (boot == null) {
            synchronized (this) {
                if (boot == null) createClient();
            }
        }

        // 一个 app 可能被部署多个实例, 每个实例可以创建多个连接
        List<Channel> chs = appNameChannelMap.get(appName);
        if (chs == null) {
            synchronized (this) {
                if (chs == null) {
                    chs = new ArrayList<>(7); appNameChannelMap.put(appName, chs);
                    adaptChannel(appName, true);
                }
            }
        } else if (chs.isEmpty()) adaptChannel(appName, true);

        if (chs.isEmpty()) throw new IllegalArgumentException("没有可用的Channel");
        else if (chs.size() == 1) return chs.get(0);
        return chs.get(new Random().nextInt(chs.size()));
    }


    /**
     * 为每个连接配置 适配 连接
     * @param appName
     * @param once 是否创建一条连接就立即返回
     */
    protected void adaptChannel(String appName, boolean once) {
        String hosts = getStr(appName + ".hosts", null);
        if (isEmpty(hosts)) {
            throw new IllegalArgumentException("未发现应用 '" + appName + "' 的连接信息配置");
        }
        List<Channel> chs = appNameChannelMap.get(appName);
        String[] arr = hosts.split(",");

        try {
            for (String hp : arr) {
                if (isBlank(hp.trim()) || !hp.contains(":")) {
                    log.warn("配置错误 {}", hosts); continue;
                }
                if (hostChannelMap.get(hp).size() > 1) continue;
                String[] a = hp.trim().split(":");
                Channel ch = boot.connect(a[0], Integer.valueOf(a[1])).sync().channel();
                chs.add(ch);
                hostChannelMap.computeIfAbsent(hp, s -> new LinkedList<>()).add(ch);
                log.info("New connection for '{}'[{}]", appName, hp);
                if (once) break;
            }
        } catch (Exception ex) {
            log.error(ex);
        }

        if (once && arr.length > chs.size()) {
            exec.execute(() -> adaptChannel(appName, false));
        }
    }


    /**
     * 创建客户端
     */
    protected void createClient() {
        if (ecMap == null) ecMap = new ConcurrentHashMap<>();
        if (appNameChannelMap == null) appNameChannelMap = new ConcurrentHashMap<>();
        String loopType = getStr("loopType", (isLinux() ? "epoll" : "nio"));
        Class ch = null;
        if ("epoll".equalsIgnoreCase(loopType)) {
            clientBoosGroup = new EpollEventLoopGroup(getInteger("client-threads-boos", 1), exec);
            ch = EpollServerSocketChannel.class;
        } else if ("nio".equalsIgnoreCase(loopType)) {
            clientBoosGroup = new NioEventLoopGroup(getInteger("client-threads-boos", 1), exec);
            ch = NioServerSocketChannel.class;
        }
        boot = new Bootstrap().group(clientBoosGroup).channel(ch)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
                            removeClientChannel(ch);
                        }
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            ByteBuf buf = (ByteBuf) msg;
                            try {
                                if (buf.readableBytes() <= 0) return;
                                byte[] bs = new byte[buf.readableBytes()];
                                buf.readBytes(bs);
                                JSONObject data = JSON.parseObject(new String(bs));
                                if ("event".equals(data.getString("type"))) {
                                    JSONObject jo = data.getJSONObject("data");
                                    EC ec = ecMap.remove(jo.getString("eId"));
                                    if (ec != null) ec.result(jo.get("result")).resume().tryFinish();
                                }
                            } finally {
                                ReferenceCountUtil.release(msg);
                            }
                        }
                    });
                }
            });
    }



    /**
     * 创建监听
     */
    protected void createServer() {
        if (exposePort == null || isEmpty(sysName)) return;
        String loopType = getStr("loopType", (isLinux() ? "epoll" : "nio"));
        Boolean shareLoop = getBoolean("shareLoop", true);
        Class ch = null;
        if ("epoll".equalsIgnoreCase(loopType)) {
            serverBoos = new EpollEventLoopGroup(getInteger("threads-boos", 1), exec);
            serverWorker = (shareLoop ? serverBoos : new EpollEventLoopGroup(getInteger("threads-worker", 1), exec));
            ch = EpollServerSocketChannel.class;
        } else if ("nio".equalsIgnoreCase(loopType)) {
            serverBoos = new NioEventLoopGroup(getInteger("threads-boos", 1), exec);
            serverWorker = (shareLoop ? serverBoos : new NioEventLoopGroup(getInteger("threads-worker", 1), exec));
            ch = NioServerSocketChannel.class;
        }
        ServerBootstrap sb = new ServerBootstrap()
            .group(serverBoos, serverWorker)
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
                                handleReceive(ctx, JSON.parseObject(new String(bs)));
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
            if (isNotEmpty(host)) sb.bind(host, exposePort).sync(); // 如果没有配置hostname, 默认绑定本地所有地址
            else sb.bind(exposePort).sync();
            log.info(
                "Started {} Server. hostname: {}, port: {}, type: {}, shareEventLoop: {}",
                getName(), (isNotEmpty(host) ? host : "0.0.0.0"), exposePort, loopType, shareLoop
            );
        } catch (Exception ex) {
            log.error(ex);
        }
    }


    /**
     * 删除客户端连接
     * @param ch
     */
    protected void removeClientChannel(Channel ch) {
        String appName = "";
        String hp = "";
        loop: for (Iterator<Map.Entry<String, List<Channel>>> it = appNameChannelMap.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<Channel>> e = it.next();
            for (Iterator<Channel> it1 = e.getValue().iterator(); it1.hasNext(); ) {
                if (it1.next() == ch) {it1.remove(); appName = e.getKey(); break loop;}
            }
        }
        loop: for (Iterator<Map.Entry<String, List<Channel>>> it = hostChannelMap.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<Channel>> e = it.next();
            for (Iterator<Channel> it1 = e.getValue().iterator(); it1.hasNext(); ) {
                if (it1.next() == ch) { it1.remove(); hp = e.getKey(); break loop; }
            }
        }
        log.info("Remove connection for '{}'[{}]", appName, hp);
    }


    /**
     * 处理数据接收
     * @param ctx
     * @param data
     */
    protected void handleReceive(ChannelHandlerContext ctx, JSONObject data) {
        String t = data.getString("type");
        if ("event".equals(t)) {
            receiveEvent(ctx, data.getJSONObject("data"));
        } else if ("ping".equals(t)) { // 用来验证此条连接是否还可用
            ctx.writeAndFlush(new JSONObject(2).fluentPut("type", "ping").fluentPut("status", "yes").toJSONString());
        } else throw new IllegalArgumentException("Not support exchange data type '" + t +"'");
    }


    /**
     * 接收远程事件调用
     * @param ctx
     * @param data
     */
    protected void receiveEvent(ChannelHandlerContext ctx, JSONObject data) {
        String from = data.getString("source");
        String eId = data.getString("eId");
        String eName = data.getString("eName");
        if (isEmpty(from)) throw new IllegalArgumentException("未知调用来源");
        else if (Objects.equals(sysName, from)) log.warn("发现循环调用链. id: {}, eName: {}", eId, eName);

        EC ec = new EC();
        ec.id(eId).async(data.getBoolean("async"));
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

        if (Boolean.TRUE.equals(data.getBoolean("reply"))) {
            ec.completeFn(ec1 -> {
                JSONObject r = new JSONObject(3);
                r.put("eId", ec.id());
                r.put("success", ec.isSuccess());
                r.put("result", ec.result);
                ctx.writeAndFlush(new JSONObject(2).fluentPut("type", "event").fluentPut("data", r).toJSONString());
            });
        }
        ep.fire(eName, ec);
    }


    /**
     * 熔断: 是否拒绝处理请求
     * @param ctx
     * @return
     */
    protected boolean fusing(ChannelHandlerContext ctx) {
        if (connCount.get() >= getInteger("maxConnection", 100)) { // 最大连接
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
