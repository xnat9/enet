package cn.xnatural.enet.server.remote;

import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import javax.annotation.Resource;
import java.util.concurrent.Executor;

class UDPServer extends ServerTpl {

    protected final Remoter        remoter;
    @Resource
    protected       Executor       exec;
    protected       EventLoopGroup boos;

    public UDPServer(Remoter remoter, EP ep, Executor exec) {
        super("tcp-server");
        this.ep = ep; this.exec = exec; this.remoter = remoter;
    }

    public void start() {
        create();
    }


    public void create() {
        String loopType = getStr("loopType", (remoter.isLinux() ? "epoll" : "nio"));
        Class ch = null;
        if ("epoll".equalsIgnoreCase(loopType)) {
            boos = new EpollEventLoopGroup(getInteger("threads-boos", 1), exec);
            ch = EpollDatagramChannel.class;
        } else if ("nio".equalsIgnoreCase(loopType)) {
            boos = new NioEventLoopGroup(getInteger("threads-boos", 1), exec);
            ch = NioDatagramChannel.class;
        }

        Bootstrap boot = new Bootstrap().group(boos)
            .channel(ch)
            .option(ChannelOption.SO_BROADCAST, true)
            .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) throws Exception {
                    //handlePacket(ctx, msg);
                }
            });
        // boot.bind(addr, port).sync();
    }
}
