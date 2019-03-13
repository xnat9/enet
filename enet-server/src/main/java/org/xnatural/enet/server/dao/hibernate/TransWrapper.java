package org.xnatural.enet.server.dao.hibernate;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.xnatural.enet.common.Log;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 事务包装器
 */
public class TransWrapper {
    protected Log            log = Log.of(getClass());
    protected SessionFactory sf;


    public TransWrapper(SessionFactory sf) {
        this.sf = sf;
    }


    public void trans(Runnable fn) {
        trans(fn, null);
    }


    public <T> T trans(Supplier<T> fn) {
        return trans(fn, null, null);
    }


    public <T> T trans(Runnable fn, Runnable successFn) {
        return trans(() -> {fn.run(); return null;}, successFn, null);
    }


    static ThreadLocal<Boolean> txFlag = ThreadLocal.withInitial(() -> false);
    /**
     * 事务包装
     * @param fn 事务函数
     * @param successFn 事务成功函数
     * @param failFn 事务失败函数
     */
    public <T> T trans(Supplier<T> fn, Runnable successFn, Consumer<Throwable> failFn) {
        Session s = sf.getCurrentSession();
        Transaction tx = s.getTransaction();
        if (txFlag.get()) { // 当前线程是否有事务存在
            try { return fn.get(); }
            catch (Throwable t) { tx.rollback(); s.close(); txFlag.set(false); throw t; }
        } else {
            tx.begin(); txFlag.set(true);
            try { T r = fn.get(); tx.commit(); if (successFn != null) successFn.run(); return r;}
            catch (Throwable t) { tx.rollback(); if (failFn != null) failFn.accept(t); throw t; }
            finally { s.close(); txFlag.set(false); }
        }
    }
}
