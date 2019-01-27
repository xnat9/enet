package org.xnatural.enet.server.http.netty;

import com.alibaba.fastjson.JSON;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * 用 netty 实现的 http server
 */
public class Netty4HttpServer extends ServerTpl {
    /**
     * http 服务监听端口
     */
    private int               port;
    /**
     * http 服务绑定地址
     */
    private String            hostname;
    private NioEventLoopGroup boosGroup;
    private NioEventLoopGroup workerGroup;


    public Netty4HttpServer() {
        setName("http-netty4");
        setPort(8080);
        setHostname("localhost");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getNs() + ".starting");
        // 先从核心取配置, 然后再启动
        coreEp.fire("env.ns", EC.of("ns", getNs()).sync(), (ec) -> {
            if (ec.result != null) {
                Map<String, Object> m = (Map) ec.result;
                port = Utils.toInteger(m.get("port"), getPort());
                hostname = (String) m.getOrDefault("hostname", getHostname());
                attrs.putAll(m);
            }
        });
        createServer();
        coreEp.fire(getNs() + ".started");
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("shutdown {} Server. hostname: {}, port: {}", getName(), getHostname(), getPort());
        if (boosGroup != null) boosGroup.shutdownGracefully();
        if (workerGroup != null && workerGroup != boosGroup) workerGroup.shutdownGracefully();
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    /**
     * 创建服务
     */
    private void createServer() {
        boolean isLinux = isLinux();
        Boolean shareLoop = getBoolean("shareLoop", true);
        boosGroup = new NioEventLoopGroup(getInteger("threads-boos", 1), coreExec);
        workerGroup = shareLoop ? boosGroup : new NioEventLoopGroup(getInteger("threads-worker", 1), coreExec);
        ServerBootstrap sb = new ServerBootstrap()
                .group(boosGroup, workerGroup)
                .channel(isLinux ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new IdleStateHandler(2, 0, 0, TimeUnit.MINUTES));
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpServerKeepAliveHandler());
                        ch.pipeline().addLast(new HttpObjectAggregator(getInteger("maxContentLength", 65536)));
                        ch.pipeline().addLast(new ChunkedWriteHandler());
                        coreEp.fire(getNs() + ".addHandler", EC.of("pipeline", ch.pipeline()).sync());
//                        ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
//                            @Override
//                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
//                                DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, OK);
//                                if (req.decoderResult().isFailure()) {
//                                    resp.setStatus(BAD_REQUEST);
//                                    resp.headers().set("content-type", "text/plain; charset=UTF-8");
//                                    ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
//                                    return;
//                                }
//                                coreExec.execute(() -> dispatch(ctx, req));
//                            }
//                        });
                    }
                })
                .option(ChannelOption.SO_BACKLOG, getInteger("backlog", 500))
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        try {
            sb.bind(getHostname(), getPort()).sync();
            log.info("Started {} Server. hostname: {}, port: {}", getName(), getHostname(), getPort());
        } catch (Exception ex) {
            log.error(ex);
        }
    }



    /**
     * 委托请求处理给mvc
     * @param ctx
     * @param req
     */
    private void dispatch(ChannelHandlerContext ctx, FullHttpRequest req) {
        // HttpUploadServerHandler 文件上传例子
        if ("/shutdown".equals(req.uri())) {
            stop();
        }
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, OK);
        coreEp.fire("server.mvc.dispatch",
                EC.of("path", req.uri()).attr("ctx", ctx)
                        .attr("method", req.method().name())
                        .attr("resolver", (Function<String, Object>) rName -> {
                            if ("paramResolver".equals(rName)) {
                                return new Function<String, Object>() {
                                    QueryStringDecoder qsd;
                                    HttpPostRequestDecoder prd;
                                    @Override
                                    public Object apply(String pName) {
                                        // application/json 取参
                                        if ("application/json".equals(req.headers().get("content-type"))) {
                                            return req.content().toString(Charset.forName("utf-8"));
                                        }
                                        // url 后边的查询参数
                                        if (qsd == null) qsd = new QueryStringDecoder(req.uri());
                                        if (qsd.parameters().containsKey(pName)) {
                                            List<String> vs = qsd.parameters().get(pName);
                                            if (vs.size() == 1) return vs.get(0);
                                            else return vs;
                                        }
                                        // post 参数
                                        if (HttpMethod.POST.equals(req.method()) && prd == null) prd = new HttpPostRequestDecoder(req);
                                        if (prd != null) {
                                            List<InterfaceHttpData> vs = prd.getBodyHttpDatas(pName);
                                            if (vs == null) return null;
                                            try {
                                                if (vs.size() == 1) {
                                                    if (InterfaceHttpData.HttpDataType.Attribute.equals(vs.get(0).getHttpDataType())) {
                                                        return ((Attribute) vs.get(0)).getValue();
                                                    }
                                                } else {
                                                    if (InterfaceHttpData.HttpDataType.Attribute.equals(vs.get(0).getHttpDataType())) {
                                                        List<String> rs = new LinkedList<>();
                                                        for (InterfaceHttpData v : vs) {
                                                            rs.add(((Attribute) v).getValue());
                                                        }
                                                        return rs;
                                                    }
                                                }
                                            } catch (Exception ex) {
                                                log.error(ex);
                                            }
                                        }
                                        return null;
                                    }
                                };
                            } else if ("requestHeaderResolver".equals(rName)) {
                                return (Function<String, String>) s -> req.headers().get(s);
                            } else if ("responseHeaders".equals(rName)) {
                                return new Map<String, String>() {
                                    @Override
                                    public int size() {
                                        return resp.headers().size();
                                    }

                                    @Override
                                    public boolean isEmpty() {
                                        return resp.headers().isEmpty();
                                    }

                                    @Override
                                    public boolean containsKey(Object key) {
                                        return resp.headers().contains(Objects.toString(resp.headers(), null));
                                    }

                                    @Override
                                    public boolean containsValue(Object value) {
                                        throw new UnsupportedOperationException();
                                    }

                                    @Override
                                    public String get(Object key) {
                                        return resp.headers().get(Objects.toString(key, null));
                                    }

                                    @Override
                                    public String put(String key, String value) {
                                        resp.headers().set(key, value);
                                        return value;
                                    }

                                    @Override
                                    public String remove(Object key) {
                                        String r = get(key);
                                        resp.headers().remove(Objects.toString(key, null));
                                        return r;
                                    }

                                    @Override
                                    public void putAll(Map<? extends String, ? extends String> m) {
                                        if (m == null) return;
                                        m.forEach((k, v) -> put(k, v));
                                    }

                                    @Override
                                    public void clear() {
                                        resp.headers().clear();
                                    }

                                    @Override
                                    public Set<String> keySet() {
                                        return resp.headers().names();
                                    }

                                    @Override
                                    public Collection<String> values() {
                                        throw new UnsupportedOperationException();
                                    }

                                    @Override
                                    public Set<Entry<String, String>> entrySet() {
                                        throw new UnsupportedOperationException();
                                    }
                                };
                            }
                            return null;
                        })
                        .attr("responseConsumer", (Consumer) o -> {
                            String t = resp.headers().get("content-type");
                            // resp.headers().contains("status")
                            if (o instanceof File) sendFile((File) o, ctx, req, resp);
                            else if ("application/json".equals(t)) {
                                String s = (o instanceof String ? (String) o : JSON.toJSONString(o));
                                resp.content().writeBytes(s.getBytes(Charset.forName("utf-8")));
                                ctx.writeAndFlush(resp);
                            } else if ("text/plan".equals(t)) {
                                if (o != null) resp.content().writeBytes(o.toString().getBytes(Charset.forName("utf-8")));
                                ctx.writeAndFlush(resp);
                            } else ctx.writeAndFlush(resp);
                        })
                        .attr("request", req),
                ec -> {
                    if (ec.noListener()) {
                        resp.setStatus(NOT_FOUND);
                        resp.headers().set("content-type", "text/plain; charset=UTF-8");
                        resp.content().writeBytes("mvc服务没有启动".getBytes(Charset.forName("utf-8")));
                        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
                    } else if (!ec.isSuccess()) {
                        resp.setStatus(INTERNAL_SERVER_ERROR);
                        resp.headers().set("content-type", "text/plain; charset=UTF-8");
                        resp.content().writeBytes(("内部错误: " + ec.ex.getMessage()).getBytes(Charset.forName("utf-8")));
                        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
                    }
                }
        );
    }


    /**
     * 响应文件
     * @param file
     * @param ctx
     * @param req
     * @param resp
     */
    private void sendFile(File file, ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse resp) {
        // resp.headers().set(HttpHeaderNames.CONTENT_TYPE, new MimetypesFileTypeMap().getContentType(file.getPath()));
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, file.length());
        // resp.headers().set("Accept-Ranges", "bytes");
        resp.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, String.format("attachment; filename=\"%s\"", file.getName()));
        if (HttpUtil.isKeepAlive(req)) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }
        ctx.write(resp);

        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            ChannelFuture sendFileFuture = null;
            ChannelFuture lcf;
            if (ctx.pipeline().get(SslHandler.class) == null) {
                sendFileFuture = ctx.write(new DefaultFileRegion(raf.getChannel(), 0, file.length()), ctx.newProgressivePromise());
                // Write the end marker.
                lcf = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            } else {
                sendFileFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf, 0, file.length(), 8192)), ctx.newProgressivePromise());
                // HttpChunkedInput will write the end marker (LastHttpContent) for us.
                lcf = sendFileFuture;
            }
            sendFileFuture.addListener(new ChannelProgressiveFutureListener() {
                @Override
                public void operationComplete(ChannelProgressiveFuture future) throws Exception {
                    log.info("file {} transfer complete.", file.getName());
                    raf.close();
                }
                @Override
                public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) throws Exception {
                    if (total < 0) { // total unknown
                        log.error(future.channel() + " Transfer progress: " + progress);
                    } else {
                        log.error(future.channel() + " Transfer progress: " + progress + " / " + total);
                    }
                }
            });
            lcf.addListener(ChannelFutureListener.CLOSE);
            if (!HttpUtil.isKeepAlive(req)) {
                // Close the connection when the whole content is written out.

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 判断系统是否为 linux 系统
     * @return
     */
    private boolean isLinux() {
        return (System.getProperty("os.name").toLowerCase(Locale.UK).trim().startsWith("linux"));
    }


    public String getHostname() {
        return hostname;
    }


    public Netty4HttpServer setHostname(String hostname) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新主机名");
        attrs.put("hostname", hostname); this.hostname = hostname;
        return this;
    }


    public int getPort() {
        return port;
    }


    public Netty4HttpServer setPort(int port) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新端口");
        attrs.put("port", port); this.port = port;
        return this;
    }
}
