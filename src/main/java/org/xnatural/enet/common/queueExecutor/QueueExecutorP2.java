package org.xnatural.enet.common.queueExecutor;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 对列执行器: 相同的执行逻辑(执行体/执行函数), 不同的参数, 同一时间点只能执行一个的情况, 还有顺序执行的特性
 * 实现: 只构建一个线程的线程池 {@link #initExecutor}, 会依次不间断的执行提交的任务
 * 默认: 当事务委托执行器不为空时, 则默认在事务内执行(即: 按事务包装的方式执行) 参见: {@link #offer}
 * 用法例子: {@link QueueExecutorManager#initQueue(Object, Function, boolean, Object...)}
 * NOTE: 不推荐手动去初始化此对象
 * ref: Disruptor
 * @param <P1> 传入的参数类型1
 * @param <P2> 传入的参数类型2
 * @param <R> 返回值类型
 * Created by xxb on 18/2/6.
 */
public class QueueExecutorP2<P1, P2, R> extends QueueExecutor {
    /**
     * 支持两个参数的 执行函数
     */
    private         BiFunction<P1, P2, R> fn;

    public QueueExecutorP2(Object key, BiFunction<P1, P2, R> fn) {
        this(key, fn, 500000);
    }


    /**
     * @param key        唯一标识
     * @param fn         支持两个参数的 执行函数
     * @param queueLimit 对列限制个数
     */
    public QueueExecutorP2(Object key, BiFunction<P1, P2, R> fn, int queueLimit) {
        super(key, queueLimit);
        this.fn = fn;
    }


    public void offer(P1 p1, P2 p2) {
        offer(p1, p2, null);
    }

    /**
     * 可以根据 回调 参数是否为 null 判断 是否执行成功
     *
     * @param callbackFn NOTE: 成功或失败的回调, 当失败时, 强制回调的传入参数为null
     */
    public void offer(P1 p1, P2 p2, Consumer<R> callbackFn) {
        offer(p1, p2, callbackFn, (callbackFn == null ? null : ((r) -> callbackFn.accept(null))));
    }


    /**
     * 根据提交的执行函数的参数, 创建执行体并添加到执行对列中去
     *
     * @param p1        执行函数需要的参数1
     * @param p2        执行函数需要的参数2
     * @param successFn 成功回调函数
     * @param failureFn 失败回调函数
     */
    public void offer(P1 p1, P2 p2, Consumer<R> successFn, Consumer<R> failureFn) {
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                if (transWrapper == null) { // 非事务
                    R r = null;
                    try {
                        r = fn.apply(p1, p2);
                    } catch (Exception e) {
                        if (failureFn != null) failureFn.accept(r); return;
                    }
                    if (successFn != null) successFn.accept(r);
                } else { // 事务
                    transWrapper.exec(key, p1, p2, fn, successFn, failureFn);
                }
            } catch (Exception ex) {
                log.error(ex, "{} error, 参数 p1: {}, p2: {}", key, p1, p2);
            } finally {
                calculateAvgTime(start);
            }
        });
    }

}
