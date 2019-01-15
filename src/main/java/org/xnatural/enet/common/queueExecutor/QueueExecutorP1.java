package org.xnatural.enet.common.queueExecutor;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 对列执行器: 相同的执行逻辑(执行体/执行函数), 不同的参数, 同一时间点只能执行一个的情况, 还有顺序执行的特性
 * 默认: 当事务委托执行器不为空时, 则默认在事务内执行(即: 按事务包装的方式执行) 参见: {@link #offer}
 * 默认: 当线程池一段时间不工作时, 就回收线程资源. {@link #initExecutor}
 * 用法例子: {@link QueueExecutorManager#initQueue(Object, Function, boolean, Object...)}
 * @param <P1> 传入的参数类型1
 * @param <R> 返回值类型
 * Created by xxb on 18/2/6.
 */
public class QueueExecutorP1<P1, R> extends QueueExecutor {
    /**
     * 执行逻辑(执行体/执行函数)
     */
    private         Function<P1, R>       fn;

    public QueueExecutorP1(Object key, Function<P1, R> fn) {
        this(key, fn, 50000);
    }

    /**
     * @param key        唯一标识
     * @param fn         支持一个参数的 执行函数
     * @param queueLimit 对列限制个数
     */
    public QueueExecutorP1(Object key, Function<P1, R> fn, int queueLimit) {
        super(key, queueLimit);
        this.fn = fn;
    }


    public void offer(P1 e) {
        offer(e, null);
    }


    /**
     * 根据提交的执行函数的参数, 创建执行体并添加到执行对列中去
     * 支持任务被事务包装执行, 当 {@link #transWrapper} 不为空时
     *
     * @param p         执行函数需要的参数
     * @param successFn 执行成功后回调(如果事务存在,则为事务成功后回调)
     * @param failureFn 失败回调函数
     */
    public void offer(P1 p, Consumer<R> successFn, Consumer<R> failureFn) {
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                if (transWrapper == null) { // 非事务
                    R r = null;
                    try {
                        r = fn.apply(p);
                    } catch (Exception e) {
                        if (failureFn != null) failureFn.accept(r); return;
                    }
                    if (successFn != null) successFn.accept(r);
                } else { // 事务
                    transWrapper.exec(key, p, fn, successFn, failureFn);
                }
            } catch (Exception ex) {
                log.error(ex, "error, 参数: {}", key, p);
            } finally {
                calculateAvgTime(start);
            }
        });
    }


    /**
     * 可以根据 回调 参数是否为 null 判断 是否执行成功
     *
     * @param p
     * @param callbackFn NOTE: 成功或失败的回调, 当失败时, 强制回调的传入参数为null
     */
    public void offer(P1 p, Consumer<R> callbackFn) {
        offer(p, callbackFn, (callbackFn == null ? null : ((r) -> callbackFn.accept(null))));
    }
}
