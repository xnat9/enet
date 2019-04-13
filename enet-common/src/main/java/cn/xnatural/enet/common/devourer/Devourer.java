package cn.xnatural.enet.common.devourer;

import cn.xnatural.enet.common.Log;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自消化器, 同一时刻只会有一个 执行体被执行
 * 核心方法: {@link #trigger()}
 */
public class Devourer {
    protected final Log             log     = Log.of(getClass());
    protected final Executor        exec;
    protected final AtomicBoolean   running = new AtomicBoolean(false);
    protected final Queue<Runnable> waiting = new ConcurrentLinkedQueue<>();
    protected final Object          key;
    /**
     * 如果为空则认为是游离的 {@link Devourer}
     */
    protected       DevourerManager dm;


    public Devourer(Object key, Executor exec) {
        this(key, exec, null);
    }


    public Devourer(Object key, Executor exec, DevourerManager dm) {
        if (key == null) throw new NullPointerException("devourer key is null");
        if (exec == null) throw new NullPointerException("executor is null");
        this.key = key;
        this.exec = exec;
        this.dm = dm;
    }


    public Devourer offer(Runnable fn) {
        if (fn == null) return this;
        waiting.offer(fn);
        trigger();
        return this;
    }


    /**
     * 不断的从 {@link #waiting} 对列中取出执行
     */
    private final void trigger() {
        if (waiting.isEmpty()) return;
        // TODO 会有 cas aba 问题?
        // 例子: org.springframework.security.config.annotation.AbstractSecurityBuilder.build
        if (!running.compareAndSet(false, true)) return;
        // 1.必须保证这里只有一个线程被执行
        // 2.必须保证不能出现情况: waiting 对列中有值, 但没有被执行
        exec.execute(() -> {
            try {
                waiting.poll().run();
            } catch (Throwable t) {
                log.error(t, getClass().getSimpleName() + ":" + key);
            } finally {
                running.set(false);
                if (!waiting.isEmpty()) trigger();
            }
        });
    }


    public DevourerManager getDevourerManager() {
        return dm;
    }


    @Override
    public String toString() {
        return "running: " + running + ", waiting count: " + waiting.size();
    }
}
