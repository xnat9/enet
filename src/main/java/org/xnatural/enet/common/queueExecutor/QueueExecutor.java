package org.xnatural.enet.common.queueExecutor;

import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.TransactionWrapper;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 对列执行器: 相同的执行逻辑(执行体/执行函数), 不同的参数, 同一时间点只能执行一个的情况, 还有顺序执行的特性
 * 实现: 只构建一个线程的线程池 {@link #initExecutor}, 会依次不间断的执行提交的任务
 * 默认: 当事务委托执行器不为空时, 则默认在事务内执行(即: 按事务包装的方式执行)
 * 默认: 当线程池一段时间不工作时, 就回收线程资源. {@link #initExecutor}
 * 用法例子: {@link QueueExecutorManager#initQueue(Object, Function, boolean, Object...)}
 * NOTE: 不推荐手动去初始化此对象
 * ref: Disruptor
 * Created by xxb on 18/2/6.
 */
class QueueExecutor {
    protected final Log                log = Log.of(getClass());
    protected final Object             key;
    /**
     * see: {@link #initExecutor}
     */
    protected       ThreadPoolExecutor executor;
    /**
     * 事务委托执行器
     */
    protected       TransactionWrapper transWrapper;
    /**
     * 执行的平均时间
     */
    protected       Float              avgTime;


    protected QueueExecutor(Object key) {
        this(key, 500000);
    }


    protected QueueExecutor(Object key, int queueLimit) {
        this.key = key;
        initExecutor(1, queueLimit);
    }


    /**
     *
     * @param key 唯一标识
     * @param threadCount 线程个数
     * @param queueLimit 对列限制个数
     */
    protected QueueExecutor(Object key, int threadCount, int queueLimit) {
        this.key = key;
        initExecutor(threadCount, queueLimit);
    }


    protected void calculateAvgTime(long start) {
        if (avgTime == null) avgTime = (float) (System.currentTimeMillis() - start);
        else avgTime = ((System.currentTimeMillis() - start) + avgTime) / 2;
    }


    /**
     * 关闭执行器
     */
    protected final void shutdown() {
        if (executor == null) return;
        if (!executor.isShutdown() && !executor.isTerminated()) {
            synchronized (this) {
                if (!executor.isShutdown() && !executor.isTerminated()) {
                    executor.shutdown();
                    executor = null;
                }
            }
        }
    }


    protected void initExecutor(int threadCount, int queueLimit) {
        executor = new ThreadPoolExecutor(
                threadCount, threadCount, 10, TimeUnit.MINUTES,
                new LinkedBlockingQueue<>(queueLimit)
        );
        executor.setThreadFactory(r -> new Thread(r, getClass().getSimpleName() + "[" + key + "]"));

        // 当线程池一段时间不工作时, 就回收线程资源
        executor.allowCoreThreadTimeOut(true);
    }


    public QueueExecutor setTransWrapper(TransactionWrapper transWrapper) {
        this.transWrapper = transWrapper;
        return this;
    }


    @Override
    public String toString() {
        return "平均执行时间: " + avgTime + " 毫秒, " + executor.toString();
    }
}
