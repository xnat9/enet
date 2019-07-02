package cn.xnatural.enet.server.remote;

import cn.xnatural.enet.common.Log;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import org.apache.commons.lang3.time.DateUtils;

import javax.annotation.Resource;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

import static cn.xnatural.enet.common.Utils.isEmpty;
import static cn.xnatural.enet.common.Utils.isNotEmpty;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * tcp server
 */
class TCPServer extends ServerTpl {

    protected final Remoter                                remoter;
    @Resource
    protected       Executor                               exec;
    protected       Integer                                port;
    protected       String                                 sysName;
    protected       EventLoopGroup                         boos;
    /**
     * 当前连接数
     */
    protected       AtomicInteger                          connCount  = new AtomicInteger(0);
    /**
     * 保存 app info 的属性信息
     */
    protected       Map<String, List<Map<String, Object>>> appInfoMap = new ConcurrentHashMap<>();
    protected       Set<String>                            boundAddrs;


    public TCPServer(Remoter remoter, EP ep, Executor exec) {
        super("tcp-server");
        this.ep = ep; this.exec = exec; this.remoter = remoter;
        ep.addListenerSource(this);
    }
    
    
    public void start() {
        // 绑定多个ip地址.ip2,ip2
        attr("bindAddrs", remoter.resolveLocalIp());
        attrs.putAll((Map) ep.fire("env.ns", getName()));
        port = getInteger("port", null);
        sysName = (String) ep.fire("sysName");
        create();
    }


    public void stop() {
        log.info("Close '{}'", getName());
        if (boos != null) boos.shutdownGracefully();
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
        String addrs = getStr("bindAddrs", null);
        if (isEmpty(addrs)) throw new IllegalArgumentException("'bindAddrs' need config");

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

        boundAddrs = Arrays.stream(addrs.split(",")).filter(s -> isNotEmpty(s)).map(addr -> {
            try {
                sb.bind(addr, port).sync();
                return addr;
            } catch (Exception e) { log.error(e); }
            return null;
        }).filter(s -> isNotEmpty(s)).collect(Collectors.toSet());
        log.info("Start listen TCP {}:{}, type: {}", boundAddrs, port, loopType);
    }


    /**
     * 处理接收来自己客户端的数据
     * @param ctx
     * @param dataStr
     */
    protected void handleReceive(ChannelHandlerContext ctx, String dataStr) {
        log.trace("Receive client '{}' data: {}", ctx.channel().remoteAddress(), dataStr);
        count(); // 统计

        JSONObject jo = JSON.parseObject(dataStr);
        String from = jo.getString("source"); // 数据来源
        if (isEmpty(from)) throw new IllegalArgumentException("Unknown source");

        String t = jo.getString("type");
        if ("event".equals(t)) {
            exec.execute(() -> {
                if (Objects.equals(sysName, from)) log.warn("Invoke self. appName '{}'", from);
                remoter.receiveEventReq(jo.getJSONObject("data"), o -> ctx.writeAndFlush(remoter.toByteBuf(o)));
            });
        } else if ("up".equals(t)) {// 应用注册在线通知
            exec.execute(() -> appUp(jo.getJSONObject("data"), ctx));
        } else if ("cmd-log".equals(t)) { // 命令行设置日志等级
            // telnet localhost 8080
            // 例: {"type":"cmd-log", "source": "xxx", "data": "cn.xnatural.enet.server.remote: debug"}$_$
            exec.execute(() -> {
                String[] arr = jo.getString("data").split(":");
                Log.setLevel(arr[0].trim(), arr[1].trim());
                ctx.writeAndFlush(Unpooled.copiedBuffer("set log level success", Charset.forName("utf-8")));
            });
        } else {
            ctx.close();
            log.error("Not support exchange data type '{}'", t);
        };
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


    /**
     * 应用上线通知
     * @param data
     * @param ctx
     */
    protected void appUp(JSONObject data, ChannelHandlerContext ctx) {
        if (data.isEmpty()) { log.warn("Register data is empty"); return;}
        log.debug("Receive register up: {}", data);
        String appName = data.getString("name");
        data.put("_time", System.currentTimeMillis());
        synchronized (appInfoMap) {
            List<Map<String, Object>> apps = appInfoMap.computeIfAbsent(appName, s -> new LinkedList<>());
            for (Iterator<Map<String, Object>> it = apps.iterator(); it.hasNext(); ) {
                if (Objects.equals(it.next().get("id"), data.get("id"))) {it.remove(); break;}
            }
            apps.add(data);
            for (Iterator<Map.Entry<String, List<Map<String, Object>>>> it = appInfoMap.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, List<Map<String, Object>>> e = it.next();
                List<Map<String, Object>> v = e.getValue();
                if (v == null || v.isEmpty()) {it.remove(); continue;}
                for (Iterator<Map<String, Object>> it2 = v.iterator(); it2.hasNext(); ) {
                    Map<String, Object> cur = it2.next();
                    if (isEmpty(cur)) it2.remove();
                    // 一段时间未上传则删除
                    // dropAppTimeout 单位: 分钟
                    else if ((System.currentTimeMillis() - (Long) cur.getOrDefault("_time", System.currentTimeMillis()) > getInteger("dropAppTimeout", 30) * 60 * 1000) && !Objects.equals(cur.get("id"), remoter.tcpClient.id)) {
                        it2.remove();
                        log.warn("Drop timeout app register info: {}", cur);
                    }
                }
                if (v == null || v.isEmpty()) it.remove();
            }

            // 添加
            ep.fire("updateAppInfo", new Object[]{data});
            appInfoMap.forEach((s, ls) -> {
                for (Map<String, Object> d : ls) {
                    if (!Objects.equals(d.get("id"), data.getString("id"))) {
                        ctx.writeAndFlush(remoter.toByteBuf(new JSONObject(2).fluentPut("type", "updateAppInfo").fluentPut("data", d)));
                    }
                }
            });
        }
    }


    /**
     * 统计每小时的处理 tcp 数据包个数
     * MM-dd HH -> 个数
     */
    protected Map<String, LongAdder> hourCount = new ConcurrentHashMap<>(3);
    protected void count() {
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH");
        boolean isNew = false;
        String hStr = sdf.format(new Date());
        LongAdder count = hourCount.get(hStr);
        if (count == null) {
            synchronized (this) {
                count = hourCount.get(hStr);
                if (count == null) {
                    count = new LongAdder(); hourCount.put(hStr, count);
                    isNew = true;
                }
            }
        }
        count.increment();
        if (isNew) {
            String lastHour = sdf.format(DateUtils.addHours(new Date(), -1));
            LongAdder c = hourCount.remove(lastHour);
            if (c != null) log.info("{} 时共处理 tcp 数据包: {} 个", lastHour, c);
        }
    }
}
