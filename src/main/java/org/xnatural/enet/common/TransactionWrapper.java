package org.xnatural.enet.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 事务委托执行器: 把一个执行体(一段执行逻辑)包装成一个事务来执行
 * Created by xxb on 18/2/6.
 */
public class TransactionWrapper {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * 新事务执行一段逻辑
     * @param source
     * @param fn
     */
    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exec(Object source, Runnable fn) {
        exec(source, fn, (Runnable) null);
    }


    /**
     * 新事务执行一段逻辑
     * @param source
     * @param fn
     * @param successFn
     */
    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exec(Object source, Runnable fn, Runnable successFn) {
        exec(source, fn, successFn, (Runnable) null);
    }


    /**
     * 新事务执行一段逻辑
     * @param source 执行来源
     * @param fn 执行体
     * @param successFn 事务成功后回调
     * @param successFn 事务失败后回调
     */
    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exec(Object source, Runnable fn, Runnable successFn, Runnable failureFn) {
        log.trace("source: {} start transaction", source);
//        if (successFn != null || failureFn != null) {
//            if (TransactionSynchronizationManager.isSynchronizationActive()) {
//                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
//                    @Override
//                    public void afterCompletion(int status) {
//                        if (STATUS_COMMITTED == status && successFn != null) {
//                            successFn.run();
//                        } else {
//                            if (failureFn != null) failureFn.run();
//                        }
//                    }
//                });
//            } else log.error("不是线程同步事务, successFn or failureFn 不起作用");
//        }
        fn.run();
        log.trace("source: {} end transaction", source);
    }


    /**
     * 新事务执行一段逻辑
     * @param source
     * @param param
     * @param fn
     * @param <T>
     * @param <R>
     */
    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T, R> void exec(Object source, T param, Function<T, R> fn) {
        exec(source, param, fn, (Consumer) null);
    }


    /**
     * 新事务执行一段逻辑
     * @param source
     * @param param
     * @param fn
     * @param successFn
     * @param <T>
     * @param <R>
     */
    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T, R> void exec(Object source, T param, Function<T, R> fn, Consumer<R> successFn) {
        exec(source, param, fn, successFn, null);
    }


    /**
     * 新事务执行一段逻辑
     * @param source 委托源
     * @param param 执行函数的参数
     * @param fn 执行体/执行函数
     * @param successFn 事务成功后回调
     * @param failureFn 事务失败后回调
     * @param <T>
     * @param <R>
     */
    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T, R> void exec(Object source, T param, Function<T, R> fn, Consumer<R> successFn, Consumer<R> failureFn) {
        log.trace("source: {} start transaction", source);
        R r = fn.apply(param);
//        if (successFn != null || failureFn != null) {
//            if (TransactionSynchronizationManager.isSynchronizationActive()) {
//                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
//                    @Override
//                    public void afterCompletion(int status) {
//                        if (STATUS_COMMITTED == status && successFn != null) {
//                            successFn.accept(r);
//                        } else {
//                            if (failureFn != null) failureFn.accept(r);
//                        }
//                    }
//                });
//            } else log.error("不是线程同步事务, successFn or failureFn 不起作用");
//        }
        log.trace("source: {} end transaction", source);
    }


    /**
     * 新事务执行一段逻辑
     * @param source
     * @param p1 参数1
     * @param p2 参数2
     * @param fn 执行体/执行函数
     * @param successFn 事务成功后回调
     * @param failureFn 事务失败后回调
     * @param <T>
     * @param <U>
     * @param <R>
     */
    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T, U, R> void exec(Object source, T p1, U p2, BiFunction<T, U, R> fn, Consumer<R> successFn, Consumer<R> failureFn) {
        log.trace("source: {} start transaction", source);
        R r = fn.apply(p1, p2);
//        if (successFn != null || failureFn != null) {
//            if (TransactionSynchronizationManager.isSynchronizationActive()) {
//                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
//                    @Override
//                    public void afterCompletion(int status) {
//                        if (STATUS_COMMITTED == status && successFn != null) {
//                            successFn.accept(r);
//                        } else {
//                            if (failureFn != null) failureFn.accept(r);
//                        }
//                    }
//                });
//            } else log.error("不是线程同步事务, successFn 不起作用");
//        }
        log.trace("source: {} end transaction", source);
    }


    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T, U, R> void exec(Object source, T p1, U p2, BiFunction<T, U, R> fn, Consumer<R> successFn) {
        exec(source, p1, p2, fn, successFn, null);
    }


    // @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T, U, R> void exec(Object source, T p1, U p2, BiFunction<T, U, R> fn) {
        exec(source, p1, p2, fn, null);
    }

}
