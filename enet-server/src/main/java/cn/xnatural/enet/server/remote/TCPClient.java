package cn.xnatural.enet.server.remote;

import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;

import javax.annotation.Resource;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import static cn.xnatural.enet.common.Utils.isBlank;
import static cn.xnatural.enet.common.Utils.isEmpty;
import static java.util.concurrent.TimeUnit.SECONDS;

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
     * @param appName 向哪个应用发数据
     * @param data 要发送的数据
     * @param completeFn 发送完成后回调函数
     */
    public void send(String appName, Object data, Consumer<Throwable> completeFn) {
        Channel ch = channel(appName);
        ch.writeAndFlush(remoter.toByteBuf(data)).addListener(f -> {
            exec.execute(() -> {
                LinkedList<Long> record = appInfoMap.get(appName).hpErrorRecord.get(ch.attr(AttributeKey.valueOf("hp")).get());
                if (f.isSuccess() && !record.isEmpty()) record.clear();
                else if (f.cause() != null) record.addFirst(System.currentTimeMillis());
                completeFn.accept(f.cause());
            });
        });
    }


    /**
     * 得到一个指向 appName 的Channel
     * @param appName
     * @return
     */
    protected Channel channel(String appName) {
        if (boot == null) {
            synchronized (this) {
                if (boot == null) create();
            }
        }

        // 一个 app 可能被部署多个实例, 每个实例可以创建多个连接
        AppInfo info = appInfoMap.get(appName);
        if (info == null) throw new IllegalArgumentException("Not found available server for '" + appName + "'");
        if (info.chs.isEmpty()) {
            try {
                info.rwlock.writeLock().lock();
                adaptChannel(info, false);
            } finally {
                info.rwlock.writeLock().unlock();
            }
        }

        // 获取一个连接Channel
        Channel ch = null;
        try {
            info.rwlock.readLock().lock();
            if (info.chs.isEmpty()) throw new IllegalArgumentException("Not found available channel for '" + appName +"'");
            else if (info.chs.size() == 1) ch = info.chs.get(0);
            else { ch = info.chs.get(new Random().nextInt(info.chs.size())); }
        } finally {
            info.rwlock.readLock().unlock();
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
            throw new IllegalArgumentException("Not found connection config for '"+ appInfo.name +"'");
        }
        if (once && appInfo.chs.size() > 0) return;
        Integer maxConnectionPerHp = getInteger("maxConnectionPerHp", 2); // 每个host:port最多可以建立多少个连接
        for (Iterator<String> it = appInfo.hps.iterator(); it.hasNext(); ) {
            String hp = it.next();
            try {
                if (isBlank(hp) || !hp.contains(":")) {
                    log.warn("Config error {}", appInfo.hps); continue;
                }
                AtomicInteger count = appInfo.hpChannelCount.get(hp);
                if (count == null) {
                    count = new AtomicInteger(0); appInfo.hpChannelCount.put(hp, count);
                }
                if (count.get() >= maxConnectionPerHp) continue;
                LinkedList<Long> errRecord = appInfo.hpErrorRecord.get(hp);
                if (errRecord == null) {
                    errRecord = new LinkedList<>(); appInfo.hpErrorRecord.put(hp, errRecord);
                }
                Long lastRefuse = errRecord.peekFirst(); // 上次被连接被拒时间
                if (lastRefuse != null && (System.currentTimeMillis() - lastRefuse < 2000)) { // 距上次连接被拒还没超过2秒, 则不必再连接
                    continue;
                }
                String[] arr = hp.split(":");
                Channel ch;
                try {
                    ChannelFuture f = boot.connect(arr[0], Integer.valueOf(arr[1]));
                    f.await(getInteger("connectTimeout", 3), SECONDS);
                    ch = f.channel();
                } catch (Exception ex) {
                    errRecord.addFirst(System.currentTimeMillis());
                    // 连接(hp(host:port))多次发生错误, 则移除此条连接配置
                    if ((appInfo.hps.size() == 1 && errRecord.size() >= 2) || (appInfo.hps.size() > 1)) {
                        errRecord.clear(); it.remove(); redeem(appInfo, hp);
                    }
                    throw ex;
                }
                ch.attr(AttributeKey.valueOf("app")).set(appInfo); ch.attr(AttributeKey.valueOf("hp")).set(hp);
                appInfo.chs.add(ch); count.incrementAndGet();
                log.info("New TCP Connection to '{}'[{}]. total count: {}", appInfo.name, hp, count);
                if (once) break;
            } catch (Exception ex) { log.error(ex); }
        }
        if (once && (appInfo.hps.size() * maxConnectionPerHp) > appInfo.chs.size()) {
            exec.execute(() -> {
                try {
                    appInfo.rwlock.writeLock().lock();
                    adaptChannel(appInfo, false);
                } finally {
                    appInfo.rwlock.writeLock().unlock();
                }
            });
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
                    ch.pipeline().addLast(new DelimiterBasedFrameDecoder(remoter.getInteger("maxFrameLength", 1024 * 1024), remoter.delimiter));
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
                                str = buf.toString(Charset.forName("utf-8"));
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
        log.debug("Receive reply from '{}': {}", ctx.channel().remoteAddress(), data);

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
        try {
            app.rwlock.writeLock().lock();
            for (Iterator<Channel> it = app.chs.iterator(); it.hasNext(); ) {
                if (it.next().equals(ch)) {it.remove(); break;}
            }
        } finally {
            app.rwlock.writeLock().unlock();
        }
        AtomicInteger count = app.hpChannelCount.get(hp); count.decrementAndGet();
        log.info("Remove TCP Connection to '{}'[{}]. left count: {}", app.name, hp, count);
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
                    else { ep.fire("sched.after", pass.removeFirst(), SECONDS, this); }
                }
            }
        };
        ep.fire("sched.after", pass.removeFirst(), SECONDS, fn);
    }


    protected class AppInfo {
        protected String                        name;
        /**
         * app 连接配置信息
         * app -> ip:port
         */
        protected Set<String>                   hps            = ConcurrentHashMap.newKeySet();
        /**
         * {@link #chs} 读写锁
         */
        protected ReadWriteLock                 rwlock         = new ReentrantReadWriteLock();
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
