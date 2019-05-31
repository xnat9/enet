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
import io.netty.util.ReferenceCountUtil;

import javax.annotation.Resource;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static cn.xnatural.enet.common.Utils.isBlank;
import static cn.xnatural.enet.common.Utils.isEmpty;

/**
 * tcp client
 */
class TCPClient extends ServerTpl {

    @Resource
    protected       Executor                   exec;
    protected final Remoter                    remoter;
    /**
     * appName -> list Channel
     */
    protected       Map<String, List<Channel>> appNameChannelMap;
    /**
     * host:port -> list Channel
     */
    protected       Map<String, List<Channel>> hostChannelMap;
    protected       EventLoopGroup             boos;
    protected       Bootstrap                  boot;


    public TCPClient(Remoter remoter, EP ep, Executor exec) {
        super("tcp-client");
        this.ep = ep; this.exec = exec; this.remoter = remoter;
    }


    public void start() {
        attrs.putAll((Map) ep.fire("env.ns", getName()));
    }


    protected void stop() {
        if (boos != null) boos.shutdownGracefully();
        boot = null;
        appNameChannelMap = null; hostChannelMap = null;
    }


    /**
     * 发送数据 到 app
     * @param appName
     * @param data
     */
    protected void send(String appName, Object data) {
        channel(appName).writeAndFlush(toByteBuf(data));
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
                if (boot == null) create();
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
     * @param appName 应用名
     * @param once 是否创建一条连接就立即返回
     */
    protected void adaptChannel(String appName, boolean once) {
        String hosts = getStr(appName + ".hosts", null);
        if (isEmpty(hosts)) {
            throw new IllegalArgumentException("Not found connection config for '" + appName + "'");
        }
        List<Channel> chs = appNameChannelMap.get(appName);
        String[] arr = hosts.split(",");

        for (String hp : arr) {
            try {
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
            } catch (Exception ex) { log.error(ex); }
        }
        if (once && arr.length > chs.size()) {
            exec.execute(() -> adaptChannel(appName, false));
        }
    }


    /**
     * 创建客户端
     */
    protected void create() {
        if (appNameChannelMap == null) appNameChannelMap = new ConcurrentHashMap<>();
        if (hostChannelMap == null) hostChannelMap = new ConcurrentHashMap<>();
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
     * 消息转换成 {@link ByteBuf}
     * @param msg
     * @return
     */
    protected ByteBuf toByteBuf(Object msg) {
        if (msg instanceof String) return Unpooled.copiedBuffer((String) msg, Charset.forName("utf-8"));
        else if (msg instanceof JSONObject) return Unpooled.copiedBuffer(((JSONObject) msg).toJSONString(), Charset.forName("utf-8"));
        else throw new IllegalArgumentException("Not support '" + getName() + "' send data type '" + (msg == null ? null : msg.getClass().getName()) + "'");
    }
}
