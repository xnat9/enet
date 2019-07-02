package cn.xnatural.enet.common;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 吞噬器, 同一时刻只会有一个 执行体被执行
 * 核心方法: {@link #trigger()}
 */
public class Devourer {
    protected final Log                 log     = Log.of(getClass());
    protected final Executor            exec;
    protected final AtomicBoolean       running = new AtomicBoolean(false);
    protected final Queue<Runnable>     waiting = new ConcurrentLinkedQueue<>();
    protected final Object              key;
    /**
     * 是否应该熔断: 暂停执行
     */
    protected       Supplier<Boolean>   pause   = () -> Boolean.FALSE;
    /**
     * 是否应该熔断: 丢弃
     */
    protected       Supplier<Boolean>   fusing  = () -> Boolean.FALSE;
    /**
     * 异常处理
     */
    protected       Consumer<Throwable> exConsumer;


    public Devourer(Object key, Executor exec) {
        if (key == null) throw new NullPointerException("devourer key is null");
        if (exec == null) throw new NullPointerException("executor is null");
        this.key = key;
        this.exec = exec;
    }


    public Devourer offer(Runnable fn) {
        if (fn == null || fusing.get()) return this;
        waiting.offer(fn);
        trigger();
        return this;
    }


    /**
     * 不断的从 {@link #waiting} 对列中取出执行
     */
    protected void trigger() {
        if (waiting.isEmpty() || pause.get()) return;
        // TODO 会有 cas aba 问题?
        if (!running.compareAndSet(false, true)) return;
        // 1.必须保证这里只有一个线程被执行
        // 2.必须保证不能出现情况: waiting 对列中有值, 但没有被执行
        exec.execute(() -> {
            try {
                waiting.poll().run();
            } catch (Throwable t) {
                if (exConsumer == null) log.error(t, getClass().getSimpleName() + ":" + key);
                else exConsumer.accept(t);
            } finally {
                running.set(false);
                if (!waiting.isEmpty()) trigger();
            }
        });
    }


    public Devourer pause(Supplier<Boolean> pause) {
        if (pause == null) throw new IllegalArgumentException("pause Supplier can not be null");
        this.pause = pause;
        return this;
    }


    public Devourer fusing(Supplier<Boolean> fusing) {
        if (fusing == null) throw new IllegalArgumentException("fusing Supplier can not be null");
        this.fusing = fusing;
        return this;
    }


    public Devourer exConsumer(Consumer<Throwable> exConsumer) {
        this.exConsumer = exConsumer;
        return this;
    }


    /**
     * 排对个数
     * @return
     */
    public int getWaitingCount() {
        return waiting.size();
    }



    public void shutdown() {
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    @Override
    public String toString() {
        return "running: " + running + ", waiting count: " + waiting.size();
    }
}
