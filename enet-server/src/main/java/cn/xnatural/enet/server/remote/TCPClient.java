package cn.xnatural.enet.server.remote;

import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import javax.annotation.Resource;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.xnatural.enet.common.Utils.isBlank;
import static cn.xnatural.enet.common.Utils.isEmpty;

/**
 * tcp client
 */
class TCPClient extends ServerTpl {

    @Resource
    protected       Executor                   exec;
    protected final Remoter                    remoter;
    protected Map<String, AppInfo>             appInfoMap = new ConcurrentHashMap<>();
    protected       EventLoopGroup             boos;
    protected       Bootstrap                  boot;


    public TCPClient(Remoter remoter, EP ep, Executor exec) {
        super("tcp-client");
        this.ep = ep; this.exec = exec; this.remoter = remoter;
    }


    public void start() {
        attrs.putAll((Map) ep.fire("env.ns", getName()));
        attrs.forEach((k, v) -> {
            if (k.endsWith(".hosts")) { // 例: app1.hosts=ip1:8201,ip2:8202,ip2:8203
                appInfoMap.computeIfAbsent(k.split("\\.")[0].trim(), (s) -> new AppInfo(s)).hps.add(((String) v).trim());
            }
        });
    }


    protected void stop() {
        if (boos != null) boos.shutdownGracefully();
        boot = null;
        appInfoMap = null;
    }


    /**
     * 发送数据 到 app
     * @param appName
     * @param data
     */
    public void send(String appName, Object data) {
        ByteBuf msg = toByteBuf(data);
        Channel ch = channel(appName, null);
        LinkedList<Long> record = appInfoMap.get(appName).hpErrorRecord.get(ch.attr(AttributeKey.valueOf("hp")).get());
        try {
            ch.writeAndFlush(msg, ch.newPromise().addListener(f -> {
                // 如果成功 则清除之前的错误记录
                if (f.isSuccess() && !record.isEmpty()) record.clear();
            }));
        } catch (Exception ex) {
            log.error("send tcp data error: {}. try again", ex.getMessage());
            record.addFirst(System.currentTimeMillis());
            ch = channel(appName, ch);
            ch.writeAndFlush(msg);
        }
    }


    /**
     * 得到一个指向 appName 的Channel
     * @param appName
     * @return
     * @throws Exception
     */
    protected Channel channel(String appName, Channel unexpected) {
        if (boot == null) {
            synchronized (this) {
                if (boot == null) create();
            }
        }

        // 一个 app 可能被部署多个实例, 每个实例可以创建多个连接
        AppInfo info = appInfoMap.get(appName);
        List<Channel> chs = info == null ? null : info.chs;
        if (chs == null) {
            synchronized (this) {
                if (chs == null) {
                    chs = info.chs = new ArrayList<>(7);
                    adaptChannel(info, true);
                }
            }
        } else if (chs.isEmpty()) adaptChannel(info, true);

        Channel ch = null;
        if (chs.isEmpty()) throw new IllegalArgumentException("Not found available channel for '" + appName +"'");
        else if (chs.size() == 1) ch = chs.get(0);
        else if (chs.size() == 2 && unexpected != null) {
            ch = chs.get(0);
            if (ch == unexpected) ch = chs.get(1);
        } else {
            while (true) {
                ch = chs.get(new Random().nextInt(chs.size()));
                if (unexpected == null) break;
                if (unexpected != ch) break;
            }
        }
        return ch;
    }


    /**
     * 为每个连接配置 适配 连接
     * @param appInfo 应用名
     * @param once 是否创建一条连接就立即返回
     */
    protected void adaptChannel(AppInfo appInfo, boolean once) {
        if (isEmpty(appInfo.hps)) {
            log.warn("Not found connection config for '{}'", appInfo.name); return;
        }
        Integer maxConnectionPerHp = getInteger("maxConnectionPerHp", 1); // 每个host:port最多可以建立多少个连接
        for (Iterator<String> it = appInfo.hps.iterator(); it.hasNext(); ) {
            String hp = it.next();
            try {
                if (isBlank(hp) || !hp.contains(":")) {
                    log.warn("Config error {}", appInfo.hps); continue;
                }
                AtomicInteger count = appInfo.hpChannelCount.get(hp);
                if (count == null) {
                    synchronized (this) {
                        if (count == null) {
                            count = new AtomicInteger(0); appInfo.hpChannelCount.put(hp, count);
                        }
                    }
                }
                if (count.get() >= maxConnectionPerHp) continue;
                LinkedList<Long> errRecord = appInfo.hpErrorRecord.get(hp);
                if (errRecord == null) {
                    synchronized (this) {
                        if (errRecord == null) {
                            errRecord = new LinkedList<>(); appInfo.hpErrorRecord.put(hp, errRecord);
                        }
                    }
                }
                Long lastRefuse = errRecord.peekFirst(); // 上次被连接被拒时间
                if (lastRefuse != null && (System.currentTimeMillis() - lastRefuse < 2000)) { // 距上次连接被拒还没超过2秒, 则不必再连接
                    continue;
                }
                String[] arr = hp.split(":");
                Channel ch;
                try {
                    ch = boot.connect(arr[0], Integer.valueOf(arr[1])).sync().channel();
                } catch (Exception ex) {
                    errRecord.addFirst(System.currentTimeMillis());
                    synchronized (this) {
                        // 连接(hp(host:port))多次发生错误, 则移除此条连接配置
                        if ((appInfo.hps.size() == 1 && errRecord.size() >= 5) || (appInfo.hps.size() > 0 && errRecord.size() >= 2)) {
                            errRecord.clear(); it.remove(); redeem(appInfo, hp);
                        }
                    }
                    throw ex;
                }
                appInfo.chs.add(ch); count.incrementAndGet();
                ch.attr(AttributeKey.valueOf("app")).set(appInfo); ch.attr(AttributeKey.valueOf("hp")).set(hp);
                log.info("New TCP Connection to '{}'[{}]", appInfo, hp);
                if (once) break;
            } catch (Exception ex) { log.error(ex); }
        }
        if (once && (appInfo.hps.size() * maxConnectionPerHp) > appInfo.chs.size()) {
            exec.execute(() -> adaptChannel(appInfo, false));
        }
    }


    /**
     * 创建客户端
     */
    protected void create() {
        String loopType = getStr("loopType", (remoter.isLinux() ? "epoll" : "nio"));
        Class ch = null;
        if ("epoll".equalsIgnoreCase(loopType)) {
            boos = new EpollEventLoopGroup(getInteger("threads-boos", 1), exec);
            ch = EpollSocketChannel.class;
        } else if ("nio".equalsIgnoreCase(loopType)) {
            boos = new NioEventLoopGroup(getInteger("threads-boos", 1), exec);
            ch = NioSocketChannel.class;
        }
        boot = new Bootstrap().group(boos).channel(ch)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
                            removeChannel(ch);
                        }
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                            log.error(cause, getName() + " error");
                        }
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            ByteBuf buf = (ByteBuf) msg;
                            String str = null;
                            try {
                                if (buf.readableBytes() <= 0) return;
                                byte[] bs = new byte[buf.readableBytes()];
                                buf.readBytes(bs); str = new String(bs, "utf-8");
                                receiveReply(ctx, str);
                            } catch (JSONException ex) {
                                log.error("Received Error Data from '{}'. data: {}, errMsg: {}", ctx.channel().remoteAddress(), str, ex.getMessage());
                                ctx.close();
                            } finally {
                                ReferenceCountUtil.release(msg);
                            }
                        }
                    });
                }
            });
    }


    /**
     * 客户端接收到服务端的响应数据
     * @param ctx
     * @param data
     */
    protected void receiveReply(ChannelHandlerContext ctx, String data) {
        log.debug("Receive server '{}' reply: {}", ctx.channel().remoteAddress(), data);

        JSONObject jo = JSON.parseObject(data);
        String type = jo.getString("type");
        if ("event".equals(type)) {
            exec.execute(() -> remoter.receiveEventResp(jo.getJSONObject("data")));
        }
    }


    /**
     * 删除连接
     * @param ch
     */
    protected void removeChannel(Channel ch) {
        AppInfo app = (AppInfo) ch.attr(AttributeKey.valueOf("app")).get();
        String hp = (String) ch.attr(AttributeKey.valueOf("hp")).get();
        for (Iterator<Channel> it = app.chs.iterator(); it.hasNext(); ) {
            if (it.next().equals(ch)) {it.remove(); break;}
        }
        app.hpChannelCount.get(hp).decrementAndGet();
        log.info("Remove TCP Connection to '{}'[{}]", app.name, hp);
    }


    /**
     * 尝试挽回 被删除 连接配置
     * @param appInfo
     * @param hp
     */
    protected void redeem(AppInfo appInfo, String hp) {
        LinkedList<Integer> pass = new LinkedList<>(Arrays.asList(5, 5, 10, 10, 20, 35, 40, 45, 50, 60, 90, 90, 120, 120, 90, 60, 60, 45, 30, 20));
        Runnable fn = new Runnable() {
            @Override
            public void run() {
                String[] arr = hp.split(":");
                try {
                    boot.connect(arr[0], Integer.valueOf(arr[1])).sync().channel().disconnect();
                    appInfo.hps.add(hp);
                    log.info("'{}' redeem success", hp);
                } catch (Exception e) {
                    if (pass.isEmpty()) { log.warn("'{}' can't redeem", hp); }
                    else { ep.fire("sched.after", pass.removeFirst(), TimeUnit.SECONDS, this); }
                }
            }
        };
        ep.fire("sched.after", pass.removeFirst(), TimeUnit.SECONDS, fn);
    }


    /**
     * 消息转换成 {@link ByteBuf}
     * @param msg
     * @return
     */
    protected ByteBuf toByteBuf(Object msg) {
        if (msg instanceof String) return Unpooled.copiedBuffer((String) msg, Charset.forName("utf-8"));
        else if (msg instanceof JSONObject) return Unpooled.copiedBuffer(((JSONObject) msg).toJSONString(), Charset.forName("utf-8"));
        else throw new IllegalArgumentException("Not support '" + getName() + "' send data type '" + (msg == null ? null : msg.getClass().getName()) + "'");
    }


    protected class AppInfo {
        protected String                        name;
        /**
         * app 连接配置信息
         * app -> ip:port
         */
        protected Set<String>                   hps            = ConcurrentHashMap.newKeySet();
        /**
         * list Channel
         */
        protected List<Channel>                 chs            = new ArrayList(7);
        /**
         * host:port -> list Channel
         */
        protected Map<String, AtomicInteger>    hpChannelCount = new ConcurrentHashMap<>();
        /**
         * host:port -> 连接异常时间
         */
        protected Map<String, LinkedList<Long>> hpErrorRecord  = new ConcurrentHashMap<>();

        public AppInfo(String name) {
            this.name = name;
        }
    }
}
