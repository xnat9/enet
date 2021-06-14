import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class TestEP {

    static final Logger log = LoggerFactory.getLogger(TestEP.class);

    public static void main(String[] args) throws Exception {
        //1. 创建一个事件中心(解析对象中的所有事件方法, 和触发发布事件)
        final EP ep = new EP(Executors.newFixedThreadPool(2, new ThreadFactory() {
            final AtomicInteger i = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "ep-" + i.incrementAndGet());
            }
        }));
        //2. 解析对象中的事件方法. 此步骤会解析出对象中所有包含@EL的方法, 并加入到 ep(事件中心)去
        TestEP source = new TestEP();
        ep.addListenerSource(source);

        // 触发并发布事件. 触发hello事件, "参数1" 参数会被传到hello事件所指向的方法的参数
        ep.fire("hello", "参数1");
        ep.fire("aa.hello", "参数1");

        // 事件执行完 回调
        ep.fire("hello", new EC().args("参数1").completeFn((ec) -> {
            // 事件结束执行
        }));
        ep.fire("hello", new EC().args("参数1").completeFn((ec) -> {
            if (ec.isSuccess()) {
                // 事件成功结束后执行
            }
        }));

        // 动态添加监听
        ep.listen("dynEvent1", () -> {
            System.out.println("执行动态事件: 无参");
        });
        ep.listen("dynEvent2", (p) -> {
            System.out.println("执行动态事件:  入参: " + p);
            return p;
        });
        ep.fire("dynEvent1");
        System.out.println(ep.fire("dynEvent2", "3333"));

        // 开启debug模式
        ep.fire("hello", EC.of(source).debug().args("参数1"));

        // 同步执行的事件, 可以直接取返回值
        Object r = ep.fire("get");

        // 1. 同步执行: 强制
        ep.fire("hello", EC.of(source).sync().args("参数1"));
        // 2. 同步执行: 默认  监听器 @EL async 设置为false

        // 默认填充参数为null
        ep.fire("hello"); // 此时会打印 hello null

        // 每次触发一个事件 都会有一个 EC 对象(事件执行上下文)
        ep.fire("ec"); // 自动创建EC对象
        ep.fire("ec", EC.of(source).attr("key1", "value1")); // 手动创建EC对象,并设置属性

        // 顺序测试
        ep.fire("order");

        // 执行次数限制
        ep.fire("once");
        ep.fire("once");

        ep.fire("error", "p1");
    }

    // 此方法被标注为一个名叫hello的事件方法.
    @EL(name = "hello")
    private void hello(String name) {
        System.out.println("hello " + name);
    }

    // 此处xx会被替换成此对象中的xx属性值 aa
    @EL(name = "${xx}.hello")
    protected void hello2(String name) {
        System.out.println("xx hello " + name);
    }

    // 同步执行(即和触发此事件的线程是一个)
    @EL(name = "get", async = false)
    private String get() {
        return "xxx";
    }

    @EL(name = "ec")
    public void ec(EC ec, String param1) {
        // 事件源
        Object s = ec.source();
        // 取事件下文中的属性
        String value1 = ec.getAttr("key1", String.class);
    }

    public String getXX() {
        return "aa";
    }

    @EL(name = "order")
    void order1() {
        log.info("同步 order1");
    }

    @EL(name = "order", async = true)
    void order2() {
        log.info("异步 order2");
    }

    @EL(name = "order", async = true)
    void order22() {
        log.info("异步 order22");
    }

    @EL(name = "order", async = true)
    void order222() {
        log.info("异步 order222");
    }

    @EL(name = "order", async = true, order = 1f)
    void order3() {
        log.info("异步 order3");
    }


    @EL(name = "once", limit = 1)
    void once() { log.info("只执行一次"); }


    @EL(name = "error")
    void error(String p1) {
        throw new RuntimeException("error");
    }

}
