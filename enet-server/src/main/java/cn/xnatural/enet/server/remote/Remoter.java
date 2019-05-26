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
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.epoll.Native;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.ReferenceCountUtil;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.xnatural.enet.common.Utils.*;
import static java.util.concurrent.TimeUnit.SECONDS;

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
    protected       EventLoopGroup             clientBoos;
    protected       Bootstrap                  boot;
    protected       EventLoopGroup             serverBoos;
    /**
     * 当前连接数
     */
    protected       AtomicInteger              connCount = new AtomicInteger(0);
    /**
     * ecId -> EC
     */
    protected       Map<String, EC>            ecMap = new ConcurrentHashMap<>();
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

        if (!ep.exist("sched.after")) throw new RuntimeException("Need sched Server!");

        createServer();
        ep.fire(getName() + ".started");
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}' Server", getName());
        if (serverBoos != null) serverBoos.shutdownGracefully();
        if (clientBoos != null) clientBoos.shutdownGracefully();
        boot = null;
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    @EL(name = "remote")
    public void invoke(EC ec, String appName, String eName, Object[] remoteMethodArgs) {
        try {
            if (isEmpty(ec.id())) ec.id(UUID.randomUUID().toString());
            JSONObject params = new JSONObject(5);
            params.put("eId", ec.id());
            // 是否需要远程响应执行结果(有完成回调函数就需要远程响应调用结果)
            boolean reply = ec.completeFn() != null; if (reply) ec.suspend(); // NOTE: 重要
            params.put("reply", reply);
            params.put("async", ec.isAsync());
            params.put("eName", eName);
            if (remoteMethodArgs != null) {
                JSONArray args = new JSONArray(remoteMethodArgs.length);
                params.put("args", args);
                for (Object arg : remoteMethodArgs) {
                    if (arg == null) args.add(new JSONObject(0));
                    else args.add(new JSONObject(2).fluentPut("type", arg.getClass().getName()).fluentPut("value", arg));
                }
            }
            ecMap.put(ec.id(), ec);
            log.debug("Fire remote event. params: {}", params);
            // 发送请求给远程应用appName执行
            channel(appName).writeAndFlush(toByteBuf(new JSONObject(3).fluentPut("type", "event").fluentPut("source", sysName).fluentPut("data", params)));
            // 超时处理
            ep.fire("sched.after", getInteger("eventTimeout", 10), SECONDS, (Runnable) () -> {
                EC e = ecMap.remove(ec.id());
                if (e != null) {
                    log.warn("Finish timeout event '{}'", e.id());
                    e.resume().tryFinish();
                }
            });
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

        if (chs.isEmpty()) throw new IllegalArgumentException("Not found available channel for '" + appName +"'");
        else if (chs.size() == 1) return chs.get(0);
        return chs.get(new Random().nextInt(chs.size()));
    }


    /**
     * 为每个连接配置 适配 连接
     * 配置例子: ip1:8201,ip2:8202,ip2:8203
     * @param appName
     * @param once 是否创建一条连接就立即返回
     */
    protected void adaptChannel(String appName, boolean once) {
        String hosts = getStr(appName + ".hosts", null);
        if (isEmpty(hosts)) {
            throw new IllegalArgumentException("Not found connection config for '" + appName + "'");
        }
        List<Channel> chs = appNameChannelMap.get(appName);
        String[] arr = hosts.split(",");

        try {
            for (String hp : arr) {
                if (isBlank(hp.trim()) || !hp.contains(":")) {
                    log.warn("Config error {}", hosts); continue;
                }
                if (hostChannelMap.get(hp.trim()) != null && hostChannelMap.get(hp.trim()).size() >= getInteger("maxConnectionPerHost", 1)) continue;
                String[] a = hp.trim().split(":");
                Channel ch = boot.connect(a[0], Integer.valueOf(a[1])).sync().channel();
                chs.add(ch);
                hostChannelMap.computeIfAbsent(hp, s -> new LinkedList<>()).add(ch);
                log.info("New TCP Connection to '{}'[{}]", appName, hp);
                if (once) break;
            }
        } catch (Exception ex) { log.error(ex); }

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
        if (hostChannelMap == null) hostChannelMap = new ConcurrentHashMap<>();
        String loopType = getStr("loopType", (isLinux() ? "epoll" : "nio"));
        Class ch = null;
        if ("epoll".equalsIgnoreCase(loopType)) {
            clientBoos = new EpollEventLoopGroup(getInteger("client-threads-boos", 1), exec);
            ch = EpollSocketChannel.class;
        } else if ("nio".equalsIgnoreCase(loopType)) {
            clientBoos = new NioEventLoopGroup(getInteger("client-threads-boos", 1), exec);
            ch = NioSocketChannel.class;
        }
        boot = new Bootstrap().group(clientBoos).channel(ch)
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
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                            log.error(cause, "client error");
                        }
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            ByteBuf buf = (ByteBuf) msg;
                            try {
                                if (buf.readableBytes() <= 0) return;
                                byte[] bs = new byte[buf.readableBytes()];
                                buf.readBytes(bs);
                                handleReply(ctx, JSON.parseObject(new String(bs, "utf-8")));
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
        Class ch = null;
        if ("epoll".equalsIgnoreCase(loopType)) {
            serverBoos = new EpollEventLoopGroup(getInteger("server-threads-boos", 1), exec);
            ch = EpollServerSocketChannel.class;
        } else if ("nio".equalsIgnoreCase(loopType)) {
            serverBoos = new NioEventLoopGroup(getInteger("server-threads-boos", 1), exec);
            ch = NioServerSocketChannel.class;
        }
        ServerBootstrap sb = new ServerBootstrap()
            .group(serverBoos)
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
                            log.debug("TCP Connection unregistered: {}", connCount);
                        }
                    });
                    // 最好是将IdleStateHandler放在入站的开头，并且重写userEventTriggered这个方法的handler必须在其后面。否则无法触发这个事件。
                    ch.pipeline().addLast(new IdleStateHandler(getLong("readerIdleTime", 10 * 60L), getLong("writerIdleTime", 0L), getLong("allIdleTime", 0L), SECONDS));
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
                            try {
                                if (buf.readableBytes() <= 0) return;
                                byte[] bs = new byte[buf.readableBytes()];
                                buf.readBytes(bs);
                                handleReceive(ctx, JSON.parseObject(new String(bs, "utf-8")));
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
            log.info("Start listen TCP {}:{}, type: {}", (isNotEmpty(host) ? host : "0.0.0.0"), exposePort, loopType);
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
        log.info("Remove TCP Connection to '{}'[{}]", appName, hp);
    }


    /**
     * 客户端接收到服务端的响应数据
     * @param ctx
     * @param data
     */
    protected void handleReply(ChannelHandlerContext ctx, JSONObject data) {
        log.debug("Receive server '{}' reply: {}", ctx.channel().remoteAddress(), data);
        if ("event".equals(data.getString("type"))) {
            exec.execute(() -> {
                JSONObject jo = data.getJSONObject("data");
                EC ec = ecMap.remove(jo.getString("eId"));
                if (ec != null) ec.result(jo.get("result")).resume().tryFinish();
            });
        }
    }


    /**
     * 处理接收来自己客户端的数据
     * @param ctx
     * @param data
     */
    protected void handleReceive(ChannelHandlerContext ctx, JSONObject data) {
        log.debug("Receive client '{}' data: {}", ctx.channel().remoteAddress(), data);

        String from = data.getString("source");
        if (isEmpty(from)) throw new IllegalArgumentException("Unknown source");
        else if (Objects.equals(sysName, from)) log.warn("Invoke self. appName", from);

        String t = data.getString("type");
        if ("event".equals(t)) {
            exec.execute(() -> {
                JSONObject d = data.getJSONObject("data");
                try { receiveEvent(ctx, d); }
                catch (Throwable ex) {
                    if (Boolean.TRUE.equals(d.getBoolean("reply"))) {
                        JSONObject r = new JSONObject(4);
                        r.put("eId", d.getString("eId"));
                        r.put("success", false);
                        r.put("result", null);
                        r.put("exMsg", isEmpty(ex.getMessage()) ? ex.getClass().getName() : ex.getMessage());
                        ctx.writeAndFlush(toByteBuf(new JSONObject(3).fluentPut("type", "event").fluentPut("source", sysName).fluentPut("data", r)));
                    }
                    log.error(ex, "invoke event error. data: {}", d);
                }
            });
        } else if ("heartbeat".equals(t)) { // 用来验证此条连接是否还可用
            receiveHeartbeat(ctx, data.getJSONObject("data"));
        } else throw new IllegalArgumentException("Not support exchange data type '" + t +"'");
    }


    /**
     * 接收远程事件调用
     * @param ctx
     * @param data
     */
    protected void receiveEvent(ChannelHandlerContext ctx, JSONObject data) {
        String eId = data.getString("eId");
        String eName = data.getString("eName");

        EC ec = new EC();
        ec.id(eId).async(data.getBoolean("async"));
        ec.args(data.getJSONArray("args") == null ? null : data.getJSONArray("args").stream().map(o -> {
            JSONObject jo = (JSONObject) o;
            String t = jo.getString("type");
            if (jo.isEmpty()) return null; // 参数为null
            else if (String.class.getName().equals(t)) return jo.getString("value");
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
                ctx.writeAndFlush(toByteBuf(new JSONObject(3).fluentPut("type", "event").fluentPut("source", sysName).fluentPut("data", r)));
            });
        }
        ep.fire(eName, ec);
    }


    /**
     * 处理来自己客户端的心跳检测
     * @param ctx
     * @param data
     */
    protected void receiveHeartbeat(ChannelHandlerContext ctx, JSONObject data) {
        ctx.writeAndFlush(toByteBuf(new JSONObject(3).fluentPut("type", "heartbeat").fluentPut("source", sysName).fluentPut("status", "yes")));
    }


    /**
     * 消息转换成 {@link ByteBuf}
     * @param msg
     * @return
     */
    protected ByteBuf toByteBuf(Object msg) {
        if (msg instanceof String) return Unpooled.copiedBuffer((String) msg, Charset.forName("utf-8"));
        else if (msg instanceof JSONObject) return Unpooled.copiedBuffer(((JSONObject) msg).toJSONString(), Charset.forName("utf-8"));
        return null;
    }


    /**
     * 熔断: 是否拒绝处理请求
     * @param ctx
     * @return
     */
    protected boolean fusing(ChannelHandlerContext ctx) {
        if (connCount.get() >= getInteger("maxConnection", 100)) { // 最大连接
            ctx.writeAndFlush(toByteBuf("server is busy"));
            ctx.close();
            return true;
        }
        return false;
    }


    /**
     * 判断系统是否为 linux 系统
     * 判断方法来源 {@link Native#loadNativeLibrary()}
     * @return
     */
    protected boolean isLinux() {
        return System.getProperty("os.name").toLowerCase(Locale.UK).trim().startsWith("linux");
    }
}
