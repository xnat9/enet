package org.xnatural.enet.common.queueExecutor;

import java.util.function.Function;

/**
 * 对列执行器: 不同的执行逻辑(执行体/执行函数), 同一时间点只能执行一个的情况, 还有顺序执行的特性
 * 实现: 只构建一个线程的线程池 {@link #initExecutor}, 会依次不间断的执行提交的任务
 * 默认: 当事务委托执行器不为空时, 则默认在事务内执行(即: 按事务包装的方式执行) 参见: {@link #offer}
 * 用法例子: {@link QueueExecutorManager#initQueue(Object, Function, boolean, Object...)}
 * Created by xxb on 18/2/6.
 */
public class QueueExecutorFn<FN extends Runnable> extends QueueExecutor {

    public QueueExecutorFn(Object key) {
        this(key, 500000);
    }


    /**
     * @param key        唯一标识
     * @param queueLimit 对列限制个数
     */
    public QueueExecutorFn(Object key, int queueLimit) {
        super(key, queueLimit);
    }


    public void offer(FN fn) {
        offer(fn, null, null);
    }


    public void offer(FN fn, FN successFn) {
        offer(fn, successFn, null);
    }


    /**
     * 根据提交的执行函数的参数, 创建执行体并添加到执行对列中去
     *
     * @param fn        主逻辑执行体
     * @param successFn 执行成功后回调(如果事务存在,则为事务成功后回调)
     * @param failureFn 失败回调函数
     */
    public void offer(FN fn, FN successFn, FN failureFn) {
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                if (transWrapper == null) { // 非事务
                    try {
                        fn.run();
                    } catch (Exception e) {
                        if (failureFn != null) failureFn.run(); return;
                    }
                    if (successFn != null) successFn.run();
                } else { // 事务
                    transWrapper.exec(key, fn, successFn, failureFn);
                }
            } catch (Exception ex) {
                log.error(ex);
            } finally {
                calculateAvgTime(start);
            }
        });
    }
}
