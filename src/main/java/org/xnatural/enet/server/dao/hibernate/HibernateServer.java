package org.xnatural.enet.server.dao.hibernate;

import org.hibernate.SessionFactory;
import org.xnatural.enet.core.ServerTpl;
import org.xnatural.enet.event.EL;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

/**
 * hibernate 服务
 */
public class HibernateServer extends ServerTpl {

    @EL(name = "sys.starting")
    public void start() {
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("default");
        SessionFactory sf = (SessionFactory) emf;
        EntityManager em = emf.createEntityManager();

    }

}
