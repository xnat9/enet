package org.xnatural.enet.server.dao.hibernate;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.xnatural.enet.common.Log;

import java.util.function.Consumer;

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


    public void trans(Runnable fn, Runnable successFn) {
        trans(fn, successFn, null);
    }


    /**
     * 事务包装
     * @param fn 事务函数
     * @param successFn 事务成功函数
     * @param failFn 事务失败函数
     */
    public void trans(Runnable fn, Runnable successFn, Consumer<Throwable> failFn) {
        Session s = sf.getCurrentSession();
        Transaction tx = s.beginTransaction();
        Throwable ex = null;
        try {
            fn.run();
        } catch (Throwable e) { ex = e; }
        if (ex == null) {
            tx.commit(); s.close();
        } else {
            tx.rollback(); s.close();
            if (failFn == null) throw new RuntimeException(ex);
            else failFn.accept(ex);
        }
        if (successFn != null) successFn.run();
    }
}
