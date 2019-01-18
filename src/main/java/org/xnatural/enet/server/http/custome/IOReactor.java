package org.xnatural.enet.server.http.custome;

import org.xnatural.enet.common.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * IO 响应器. 用于http
 */
class IOReactor {
    final   Log          log = Log.of(getClass());
    private HttpServer   hs;
    private Executor     exec;
    /**
     * nio 选择器
     */
    private Selector     selector;
    /**
     * bio ServerSocket
     */
    private ServerSocket ss;


    IOReactor(HttpServer hs, Executor exec) {
        this.hs = hs;
        this.exec = exec;
    }

    void start() throws IOException {
        boolean nio = hs.getBoolean("nio", Boolean.TRUE);
        if (nio) nio();
        else bio();
    }


    void stop() throws IOException {
        if (selector != null) selector.close();
        if (ss != null) ss.close();
    }


    /**
     * bio 模式
     */
    private void bio() throws IOException {
        ss = new ServerSocket();
        ss.bind(new InetSocketAddress(hs.getHostname(), hs.getPort()), hs.getInteger("backlog", 100));
        exec.execute(this::startBioAccept);
    }


    /**
     * nio 模式
     * @throws IOException
     */
    private void nio() throws IOException {
        selector = Selector.open();
        ServerSocketChannel sc = ServerSocketChannel.open();
        sc.configureBlocking(false);
        sc.socket().bind(new InetSocketAddress(hs.getHostname(), hs.getPort()), hs.getInteger("backlog", 100)); // 绑定端口
        sc.register(selector, SelectionKey.OP_ACCEPT);
        exec.execute(this::startNioEventListen);
    }


    /**
     * bio socket accept
     */
    private void startBioAccept() {
        while (true) {
            try {
                Socket s = ss.accept();
                exec.execute(() -> hs.toRequest(s));
            } catch (IOException e) {
                // TODO 怎么做?
                log.error(e);
            }
        }
    }


    /**
     * nio 事件监听
     * {@link SelectionKey#OP_ACCEPT, 新连接事件} http连接
     * {@link SelectionKey#OP_READ, 数据读取事件} http请求数据
     * {@link SelectionKey#OP_WRITE, 数据写入事件} http响应数据
     */
    private void startNioEventListen() {
        long selectTimeOut = hs.getLong("io-reactor.selector.timeout", 1000L);
        while (true) { // 不停的遍历是否有新的io事件
            try {
                // 这里不能用 selector.select(). 因为会阻塞
                final int readyCount = selector.select(selectTimeOut);
                if (readyCount > 0) {
                    final Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    for (final SelectionKey key : selectedKeys) {
                        processEvent(key);
                    }
                    selectedKeys.clear();
                }
            } catch (ClosedSelectorException ex) {
                break;
            } catch (Exception ex) {
                // TODO 重启?
                log.error(ex);
            }
        }
    }


    private void processEvent(SelectionKey key) {
        if (key.isAcceptable()) {
            accept(key);
        } else if (key.isConnectable()) {
            // ignore
        } else if (key.isReadable()) {
            key.interestOps(0);
            byte[] bs = new byte[16364];
            ByteBuffer bb = ByteBuffer.wrap(bs);
            try {
                int res = ((SocketChannel) key.channel()).read(bb);
                if (res > 0) { // TODO 为什么会没有读到数据?
                    key.attach(bb);
                    exec.execute(() -> hs.toRequest(key));
                }
            } catch (IOException e) {
                log.error(e);
            }
        } else if (key.isWritable()) {
            // hs.write((SocketChannel) key.channel());
            // key.interestOps(SelectionKey.OP_READ);
        }
    }


    private void accept(SelectionKey key) {
        final ServerSocketChannel scc = (ServerSocketChannel) key.channel();
        while (true) {
            try {
                SocketChannel sc = scc.accept();
                if (sc == null) break; // TODO 为什么会等于空
                sc.configureBlocking(false);
                SelectionKey k = sc.register(selector, SelectionKey.OP_READ);
            } catch (IOException e) {
                // TODO 这是什么情况
                log.error(e);
            }
        }
    }
}
