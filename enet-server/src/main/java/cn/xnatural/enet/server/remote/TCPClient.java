package cn.xnatural.enet.server.remote;

import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
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
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

import static cn.xnatural.enet.common.Utils.*;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * tcp client
 */
class TCPClient extends ServerTpl {

    @Resource
    protected       Executor       exec;
    protected final Remoter        remoter;
    protected Map<String, AppInfo> appInfoMap = new ConcurrentHashMap<>();
    protected       EventLoopGroup boos;
    protected       Bootstrap      boot;
    /**
     * 服务中心系统名字.
     * 会把 appName指向的系统 当作自己的服务注册服务. 并向 appName指向的系统注册自己
     */
    protected String               rcAppName;


    public TCPClient(Remoter remoter, EP ep, Executor exec) {
        super("tcp-client");
        this.ep = ep; this.exec = exec; this.remoter = remoter;
        ep.addListenerSource(this);
    }


    public void start() {
        attrs.putAll((Map) ep.fire("env.ns", getName()));
        rcAppName = getStr("registrationCenterAppName", "rc");
        attrs.forEach((k, v) -> {
            if (k.endsWith(".hosts")) { // 例: app1.hosts=ip1:8201,ip2:8202,ip2:8203
                String appName = k.split("\\.")[0].trim();
                for (String hp : ((String) v).split(",")) {
                    String[] arr = hp.trim().split(":");
                    appInfoMap.computeIfAbsent(appName, (s) -> new AppInfo(s)).hps.add(arr[0].trim() + ":" + Integer.valueOf(arr[1]));
                }
            }
        });
    }


    protected void stop() {
        if (boos != null) boos.shutdownGracefully();
        boot = null;
        appInfoMap = null;
    }


    @EL(name = "sys.started")
    protected void sysStarted() { register(); }


    /**
     * 向注册服务注册自己
     */
    protected void register() {
        AppInfo ai = appInfoMap.get(rcAppName); // 查找自己的注册中心服务
        if (ai != null) { // 向服务中心注册自己
            JSONObject data = new JSONObject(2);
            ep.fire("http.port", EC.of(this).sync().completeFn(ec -> {
                if (!ec.isNoListener()) {
                    String ip = (String) ep.fire("http.getHostname");
                    if (isEmpty(ip) || "localhost".equals(ip) || "127.0.0.1".equals(ip)) {
                        ip = resolveLocalIp();
                    }
                    if (isNotEmpty(ip)) data.put("http", ip + ":" + ec.result);
                }
            }));
            if (remoter.tcpServer.port != null) {
                String ip = remoter.tcpServer.getStr("hostname", resolveLocalIp());
                if (isNotEmpty(ip)) data.put("tcp", ip + ":" + remoter.tcpServer.port);
            }
            if (!data.isEmpty()) {
                log.info("'{}' register up info: {} to '{}'", remoter.sysName, data, ai.name);
                send(ai.name, new JSONObject(3).fluentPut("type", "up").fluentPut("source", remoter.sysName).fluentPut("data", data), ex -> {
                    if (ex != null) {
                        log.warn(ex, "register to sc server fail");
                    }
                });
            }
        }
    }



    /**
     * 更新app 信息
     * @param appName
     * @param infos
     */
    @EL(name = "updateAppInfo")
    protected void updateAppInfo(String appName, JSONArray infos) {
        log.info("update app '{}' info: {}", appName, infos);
        AppInfo app = appInfoMap.get(appName);
        if (app == null) {
            synchronized (this) {
                app = appInfoMap.get(appName);
                if (app == null) {
                    app = new AppInfo(appName); appInfoMap.put(appName, app);
                }
            }
        }
        for (Object o : infos) {
            JSONObject jo = (JSONObject) o;
            app.hps.add(jo.getString("tcp"));
        }
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
        if (info == null) { throw new IllegalArgumentException("Not found available server for '" + appName + "'"); }
        if (info.chs.isEmpty()) adaptChannel(info, true);

        // 获取一个连接Channel
        Channel ch = null;
        try {
            info.rwLock.readLock().lock();
            if (info.chs.isEmpty()) throw new IllegalArgumentException("Not found available channel for '" + appName +"'");
            else if (info.chs.size() == 1) ch = info.chs.get(0);
            else { ch = info.chs.get(new Random().nextInt(info.chs.size())); }
        } finally {
            info.rwLock.readLock().unlock();
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
        try {
            appInfo.rwLock.writeLock().lock();
            for (Iterator<String> it = appInfo.hps.iterator(); it.hasNext(); ) {
                String hp = it.next();
                if (isBlank(hp) || !hp.contains(":")) {
                    log.warn("Config error {}", appInfo.hps); continue;
                }
                AtomicInteger count = appInfo.hpChannelCount.get(hp);
                if (count == null) { count = new AtomicInteger(0); appInfo.hpChannelCount.put(hp, count); }
                if (count.get() >= maxConnectionPerHp) continue;
                LinkedList<Long> errRecord = appInfo.hpErrorRecord.get(hp);
                if (errRecord == null) { errRecord = new LinkedList<>(); appInfo.hpErrorRecord.put(hp, errRecord); }
                Long lastRefuse = errRecord.peekFirst(); // 上次被连接被拒时间
                if (!once && lastRefuse != null && (System.currentTimeMillis() - lastRefuse < 2000)) { // 距上次连接被拒还没超过2秒, 则不必再连接
                    continue;
                }
                String[] arr = hp.split(":");
                try {
                    ChannelFuture f = boot.connect(arr[0], Integer.valueOf(arr[1]));
                    f.sync().await(getInteger("connectTimeout", 3), SECONDS);
                    Channel ch = f.channel();
                    ch.attr(AttributeKey.valueOf("app")).set(appInfo); ch.attr(AttributeKey.valueOf("hp")).set(hp);
                    appInfo.chs.add(ch); count.incrementAndGet();
                    log.info("New TCP Connection to '{}'[{}]. total count: {}", appInfo.name, hp, count);
                    if (once) break;
                } catch (Exception ex) {
                    errRecord.addFirst(System.currentTimeMillis());
                    // 多个连接配置, 当某个连接(hp(host:port))多次发生错误, 则移除此条连接配置
                    if (appInfo.hps.size() > 1) { errRecord.clear(); it.remove(); redeem(appInfo, hp); }
                    log.warn(ex, "Connect Error to '{}'[{}]", appInfo.name, hp);
                }
            }
        } finally {
            appInfo.rwLock.writeLock().unlock();
        }

        // 立即返回, 已有成功的连接, 少于最多连接
        if (once && appInfo.chs.size() > 0 && (appInfo.hps.size() * maxConnectionPerHp) > appInfo.chs.size()) {
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
     * @param dataStr
     */
    protected void receiveReply(ChannelHandlerContext ctx, String dataStr) {
        log.debug("Receive reply from '{}': {}", ctx.channel().remoteAddress(), dataStr);

        JSONObject jo = JSON.parseObject(dataStr);
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
        if (app == null) return;
        String hp = (String) ch.attr(AttributeKey.valueOf("hp")).get();
        try {
            app.rwLock.writeLock().lock();
            for (Iterator<Channel> it = app.chs.iterator(); it.hasNext(); ) {
                if (it.next().equals(ch)) {it.remove(); break;}
            }
        } finally {
            app.rwLock.writeLock().unlock();
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
        LinkedList<Integer> pass = new LinkedList<>();
        for (String s : getStr("redeemFrequency", "5, 10, 5, 10, 20, 35, 40, 45, 50, 60, 90, 120, 90, 120, 90, 60, 90, 60, 90, 60, 90, 120, 300, 120, 600, 300").split(",")) {
            try {
                pass.add(Integer.valueOf(s.trim()));
            } catch (Exception ex) {
                log.warn("Config error property '{}'. {}", getName() + ".redeemFrequency", ex.getMessage());
            }
        }
        Runnable fn = new Runnable() {
            @Override
            public void run() {
                String[] arr = hp.split(":");
                try {
                    ChannelFuture f = boot.connect(arr[0], Integer.valueOf(arr[1]));
                    f.sync().await(getInteger("connectTimeout", 3), SECONDS);
                    f.channel().disconnect();
                    appInfo.hps.add(hp);
                    log.info("'{}' redeem success", hp);
                } catch (Exception e) {
                    if (pass.isEmpty()) { log.warn("'{}' can't redeem", hp); }
                    else {
                        log.warn("'{}' try redeem fail", hp);
                        ep.fire("sched.after", pass.removeFirst(), SECONDS, this);
                    }
                }
            }
        };
        ep.fire("sched.after", pass.removeFirst(), SECONDS, fn);
    }


    /**
     * 解析出本地ip
     * @return
     */
    protected String resolveLocalIp() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface current = en.nextElement();
                if (!current.isUp() || current.isLoopback() || current.isVirtual()) continue;
                Enumeration<InetAddress> addresses = current.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    return addr.getHostAddress();
                }
            }
        } catch (SocketException e) { log.error(e); }
        return null;
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
        protected ReadWriteLock                 rwLock         = new ReentrantReadWriteLock();
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
