package cn.xnatural.enet.server.remote;

import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import cn.xnatural.enet.server.ServerTpl;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.netty.channel.epoll.Native;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static cn.xnatural.enet.common.Utils.isEmpty;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 由netty实现的远程交互服务. 远程事件
 * @author xiangxb, 2019-05-18
 */
public class Remoter extends ServerTpl {
    protected final AtomicBoolean   running = new AtomicBoolean(false);
    @Resource
    protected       Executor        exec;
    /**
     * ecId -> EC
     */
    protected       Map<String, EC> ecMap   = new ConcurrentHashMap<>();
    /**
     * 系统名字(标识)
     */
    protected       String          sysName;
    protected       TCPClient       tcpClient;
    protected       TCPServer       tcpServer;


    public Remoter() { super("remote"); }
    public Remoter(String name) { super(name); }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (ep == null) ep = new EP();
        ep.fire(getName() + ".starting");

        if (!ep.exist("sched.after")) throw new RuntimeException("Need sched Server!");

        attrs.putAll((Map) ep.fire("env.ns", getName()));
        sysName = (String) ep.fire("sysName");

        tcpClient = new TCPClient(this, ep, exec);
        tcpClient.start();

        tcpServer = new TCPServer(this, ep, exec);
        tcpServer.start();

        ep.fire(getName() + ".started");
    }


    @EL(name = "sys.stopping")
    public void stop() {
        if (tcpClient != null) tcpClient.stop();
        if (tcpServer != null) tcpServer.stop();
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    /**
     * 调用远程事件
     * 执行流程: 1. 客户端发送事件:  {@link #sendEvent(EC, String, String, Object[])}
     *          2. 服务端接收到事件: {@link #receiveEventReq(JSONObject, Consumer)}
     *          3. 客户端接收到返回: {@link #receiveEventResp(JSONObject)}
     * @param ec
     * @param appName 应用名字
     * @param eName 要触发的事件名
     * @param remoteMethodArgs 远程事件监听方法的参数
     */
    @EL(name = "remote")
    protected void sendEvent(EC ec, String appName, String eName, Object[] remoteMethodArgs) {
        if (tcpClient == null) throw new RuntimeException(getName() + " not is running");
        try {
            if (isEmpty(ec.id())) ec.id(UUID.randomUUID().toString());
            JSONObject params = new JSONObject(5);
            params.put("eId", ec.id());
            // 是否需要远程响应执行结果(有完成回调函数就需要远程响应调用结果)
            boolean reply = ec.completeFn() != null; if (reply) ec.suspend(); // NOTE: 重要
            params.put("reply", reply);
            params.put("async", ec.isForceAsync());
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
            tcpClient.send(appName, new JSONObject(3).fluentPut("type", "event").fluentPut("source", sysName).fluentPut("data", params));
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
     * 接收远程事件返回的数据. 和 {@link #sendEvent(EC, String, String, Object[])} 对应
     * @param data
     */
    protected void receiveEventResp(JSONObject data) {
        EC ec = ecMap.remove(data.getString("eId"));
        if (ec != null) ec.result(data.get("result")).resume().tryFinish();
    }


    /**
     * 接收远程事件的执行请求
     * @param data 数据
     * @param reply 响应回调(传参为响应的数据)
     */
    protected void receiveEventReq(JSONObject data, Consumer<Object> reply) {
        boolean fReply = Boolean.TRUE.equals(data.getBoolean("reply")); // 是否需要响应
        try {
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

            if (fReply) {
                ec.completeFn(ec1 -> {
                    JSONObject r = new JSONObject(3);
                    r.put("eId", ec.id());
                    r.put("success", ec.isSuccess());
                    r.put("result", ec.result);
                    reply.accept(new JSONObject(3).fluentPut("type", "event").fluentPut("source", sysName).fluentPut("data", r));
                });
            }
            ep.fire(eName, ec);
        } catch (Exception ex) {
            if (fReply) {
                JSONObject r = new JSONObject(4);
                r.put("eId", data.getString("eId"));
                r.put("success", false);
                r.put("result", null);
                r.put("exMsg", isEmpty(ex.getMessage()) ? ex.getClass().getName() : ex.getMessage());
                reply.accept(new JSONObject(3).fluentPut("type", "event").fluentPut("source", sysName).fluentPut("data", r));
            }
            log.error(ex, "invoke event error. data: " + data);
        }
    }


    /**
     * 处理来自己客户端的心跳检测
     * @param data
     * @param reply
     */
    protected void receiveHeartbeat(JSONObject data, Consumer<Object> reply) {
        reply.accept(new JSONObject(3).fluentPut("type", "heartbeat").fluentPut("source", sysName).fluentPut("status", "yes"));
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