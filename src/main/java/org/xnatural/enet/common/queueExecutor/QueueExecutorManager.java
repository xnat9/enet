package org.xnatural.enet.common.queueExecutor;

import org.xnatural.enet.common.TransactionWrapper;
import org.xnatural.enet.common.Utils;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 推荐用 {@link org.xnatural.enet.common.devourer.Devourer}
 * 对列执行器 {@link QueueExecutor } 管理器
 * 所有的执行器{@link QueueExecutor } 都应该被此manager 所管理
 * 1.先初始化一个执行器: {@link #initQueue}
 * 2.再往里边扔要执行的参数: {@link #enQueue}
 * Created by xxb on 18/2/6.
 */
// @Component
public class QueueExecutorManager {
    private final Map<Object, QueueExecutor> queueExecutorMap = new ConcurrentHashMap<>(7);
    /**
     * queue key 映射(多个key可以共享同一个 {@link QueueExecutor })
     */
    private final Map<Object, Object> queueKeyMap = new ConcurrentHashMap<>();

    @Resource
    private TransactionWrapper transWrapper;


    @PreDestroy
    public void stop() {
        queueExecutorMap.forEach((type, queueExecutor) -> queueExecutor.shutdown());
    }


    /**
     * 初始化一个对列执行器{@link QueueExecutorP1}
     *
     * @param queueKey         对列执行器{@link QueueExecutorP1} 的 key
     * @param fn               fn 执行体(执行函数)
     * @param transactionAware 是否把fn包装成一个事务来执行
     * @param aliases          别名, 即和 queueKey 共用一个 对列执行器{@link QueueExecutorP1}
     * @param <P>              执行函数的参数类型
     * @param <R>              执行函数的返回值类型
     * @return 对列执行器{@link QueueExecutor}
     */
    public <P, R> QueueExecutorP1 initQueue(Object queueKey, Function<P, R> fn, boolean transactionAware, Object... aliases) {
        Object key = translateKey(queueKey);
        return (QueueExecutorP1) queueExecutorMap.computeIfAbsent(
                key,
                p -> {
                    queueKeyMap.put(key, key);
                    if (queueKey instanceof Class) queueKeyMap.put(queueKey, key);
                    if (aliases != null && aliases.length > 0) {
                        for (Object alias : aliases) queueKeyMap.put(alias, key);
                    }
                    return new QueueExecutorP1(key, fn);
                })
                .setTransWrapper(transactionAware ? transWrapper : null);
    }


    /**
     * 初始化一个 执行函数支持两个参数的 对列执行器{@link QueueExecutorP2}
     *
     * @param queueKey
     * @param fn               BiFunction 执行体(执行函数)
     * @param transactionAware 是否把fn包装成一个事务来执行
     * @param aliases          别名, 即和 queueKey 共用一个 对列执行器{@link QueueExecutorP2}
     * @param <P1>             执行函数的第一个参数类型
     * @param <P2>             执行函数的第二个参数类型
     * @param <R>              执行函数的返回值类型
     * @return 对列执行器{@link QueueExecutor}
     */
    public <P1, P2, R> QueueExecutorP2 initQueue(Object queueKey, BiFunction<P1, P2, R> fn, boolean transactionAware, Object... aliases) {
        Object key = translateKey(queueKey);
        return (QueueExecutorP2) queueExecutorMap.computeIfAbsent(
                key,
                p -> {
                    queueKeyMap.put(key, key);
                    if (queueKey instanceof Class) queueKeyMap.put(queueKey, key);
                    if (Utils.isNotEmpty(aliases)) {
                        for (Object alias : aliases) queueKeyMap.put(alias, key);
                    }
                    return new QueueExecutorP2(key, fn);
                })
                .setTransWrapper(transactionAware ? transWrapper : null);
    }


    /**
     * 初始化一个 执行函数 对列执行器{@link QueueExecutorFn}
     *
     * @param queueKey
     * @param transactionAware 是否把fn包装成一个事务来执行
     * @param aliases          别名, 即和 queueKey 共用一个 对列执行器{@link QueueExecutorFn}
     * @return 对列执行器{@link QueueExecutor}
     */
    public QueueExecutorFn initQueue(Object queueKey, boolean transactionAware, Object... aliases) {
        Object key = translateKey(queueKey);
        return (QueueExecutorFn) queueExecutorMap.computeIfAbsent(
                key,
                p -> {
                    queueKeyMap.put(key, key);
                    if (queueKey instanceof Class) queueKeyMap.put(queueKey, key);
                    if (Utils.isNotEmpty(aliases)) {
                        for (Object alias : aliases) queueKeyMap.put(alias, key);
                    }
                    return new QueueExecutorFn(key);
                })
                .setTransWrapper(transactionAware ? transWrapper : null);
    }


    public <P, R> QueueExecutorP1 initQueue(Object queueKey, Function<P, R> fn) {
        return initQueue(queueKey, fn, true, null);
    }


    public <P, R> QueueExecutorP1 initQueue(Object queueKey, Function<P, R> fn, Object... aliases) {
        return initQueue(queueKey, fn, true, aliases);
    }


    public <P1, P2, R> QueueExecutorP2 initQueue(Object queueKey, BiFunction<P1, P2, R> fn) {
        return initQueue(queueKey, fn, true, null);
    }


    public <P1, P2, R> QueueExecutorP2 initQueue(Object queueKey, BiFunction<P1, P2, R> fn, Object... aliases) {
        return initQueue(queueKey, fn, true, aliases);
    }


    public QueueExecutorFn initQueue(Object queueKey, Object... aliases) {
        return initQueue(queueKey, true, aliases);
    }


    /**
     * 提交执行参数到对应的对列执行器{@link QueueExecutor}中执行
     *
     * @param queueKey 对应那个 {@link QueueExecutor}
     * @param p        参数
     * @param callback 成功后回调
     * @param <P>
     * @return {@link QueueExecutor}
     */
    public <P, R> QueueExecutorP1 enQueue(Object queueKey, Consumer<R> callback, P p) {
        QueueExecutorP1 queue = (QueueExecutorP1) findQueue(queueKey);
        if (queue == null) throw new IllegalArgumentException(
                "未找到对应的执行器: " + queueKey + ", 请确保已用方法 initQueue 初始化过了"
        );
        queue.offer(p, callback);
        return queue;
    }


    /**
     * 提交执行参数到对应的对列执行器{@link QueueExecutor}中执行
     *
     * @param queueKey 对列执行器的key
     * @param p1       参数1
     * @param p2       参数2
     * @param callback 成功后回调
     * @param <P1>
     * @param <P2>
     * @param <R>
     * @return
     */
    public <P1, P2, R> QueueExecutorP2 enQueue(Object queueKey, Consumer<R> callback, P1 p1, P2 p2) {
        QueueExecutorP2 queue = (QueueExecutorP2) findQueue(queueKey);
        if (queue == null) throw new IllegalArgumentException(
                "未找到对应的执行器: " + queueKey + ", 请确保已用方法 initQueue 初始化过了"
        );
        queue.offer(p1, p2, callback);
        return queue;
    }


    /**
     * 提交执行体到对应的对列执行器{@link QueueExecutorFn}中执行
     *
     * @param queueKey 对列执行器的key
     * @param fn 执行体
     * @param successFn 成功回调
     * @param failureFn 失败回调
     * @return QueueExecutorFn
     */
    public QueueExecutorFn enQueue(Object queueKey, Runnable fn, Runnable successFn, Runnable failureFn) {
        QueueExecutorFn queue = (QueueExecutorFn) findQueue(queueKey);
        if (queue == null) queue = initQueue(queueKey, true, null);
        queue.offer(fn, successFn, failureFn);
        return queue;
    }


    public QueueExecutorFn enQueue(Object queueKey, Runnable fn) {
        return enQueue(queueKey, fn, null, null);
    }


    public <P> QueueExecutorP1 enQueue(P p) {
        return enQueue(p, (Consumer) null);
    }


    /**
     * 提交执行参数到对应的对列执行器中执行
     *
     * @param p        Note: 默认用e的Class去匹配对应的执行器
     * @param callback
     * @param <P>
     * @param <R>
     * @return
     */
    public <P, R> QueueExecutorP1 enQueue(P p, Consumer<R> callback) {
        return enQueue(p.getClass(), callback, p);
    }


    public <P1, P2> QueueExecutorP2 enQueue(P1 p1, P2 p2) {
        return enQueue(p1.getClass(), null, p1, p2);
    }


    /**
     * @param callback
     * @param p1       参数1. Note: 默认用p1的 Class 去匹配对应的执行器
     * @param p2       参数2
     * @param <P1>
     * @param <P2>
     * @param <R>
     * @return
     */
    public <P1, P2, R> QueueExecutorP2 enQueue(P1 p1, P2 p2, Consumer<R> callback) {
        return enQueue(p1.getClass(), callback, p1, p2);
    }


    private final QueueExecutor findQueue(Object key) {
        Object k = queueKeyMap.get(key);
        if (k == null) k = queueKeyMap.get(translateKey(key));
        return queueExecutorMap.get(k);
    }


    private Object translateKey(Object queueKey) {
        // 如果为 Class 则取Class的simpleName 作为对列执行器的key
        if (queueKey instanceof Class) {
            return ((Class) queueKey).getSimpleName();
        }
        return queueKey;
    }


    @Override
    public String toString() {
        return queueExecutorMap.toString();
    }
}
