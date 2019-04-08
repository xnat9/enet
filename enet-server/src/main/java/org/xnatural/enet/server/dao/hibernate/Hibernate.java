package org.xnatural.enet.server.dao.hibernate;

import org.hibernate.SessionFactory;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import javax.annotation.PostConstruct;
import javax.persistence.Entity;
import javax.persistence.EntityManagerFactory;
import javax.persistence.SharedCacheMode;
import javax.persistence.ValidationMode;
import javax.persistence.spi.ClassTransformer;
import javax.persistence.spi.PersistenceUnitInfo;
import javax.persistence.spi.PersistenceUnitTransactionType;
import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static javax.persistence.SharedCacheMode.UNSPECIFIED;
import static org.xnatural.enet.common.Utils.*;

/**
 * hibernate 服务
 * 暴露出 {@link EntityManagerFactory} 实例(所有的数据库操作入口)
 */
public class Hibernate extends ServerTpl {
    protected DataSource     ds;
    protected SessionFactory sf;
    protected TransWrapper   tm;
    /**
     * 实体扫描
     */
    protected List<Class>    entityScan = new LinkedList<>();
    /**
     * repo 扫描
     */
    protected List<Class>    repoScan   = new LinkedList<>();
    /**
     * 被管理的实体类名
     */
    protected List<String>   entities   = new LinkedList<>();


    public Hibernate() {
        setName("dao");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (exec == null) initExecutor();
        if (ep == null) ep = new EP(exec);
        ep.fire(getName() + ".starting");
        attrs.put("hibernate.physical_naming_strategy", "org.xnatural.enet.server.dao.hibernate.SpringPhysicalNamingStrategy");
        attrs.put("hibernate.implicit_naming_strategy", "org.xnatural.enet.server.dao.hibernate.SpringImplicitNamingStrategy");
        attrs.put("hibernate.current_session_context_class", "thread");
        attrs.putAll((Map) ep.fire("env.ns", getName()));

        for (String s : getStr("entity-scan", "").split(",")) { // 扫描entity
            if (s == null || s.trim().isEmpty()) continue;
            try {
                entityScan.add(Class.forName(s.trim()));
            } catch (ClassNotFoundException e) {
                log.error("not found class: " + s);
            }
        }
        for (String s : getStr("repo-scan", "").split(",")) { // 扫描repo
            if (s == null || s.trim().isEmpty()) continue;
            try {
                repoScan.add(Class.forName(s.trim()));
            } catch (ClassNotFoundException e) {
                log.error("not found class: " + s);
            }
        }

        sf = (SessionFactory) new HibernatePersistenceProvider().createContainerEntityManagerFactory(createPersistenceUnit(), attrs);
        exposeBean(sf, "entityManagerFactory", "sessionFactory");
        tm = new TransWrapper(sf); exposeBean(tm, "transManager");
        repoCollect();
        log.info("Started {}(Hibernate) Server", getName());
        ep.fire(getName() + ".started");
    }


    @EL(name = "sys.stopping")
    public void stop() {
        log.info("Shutdown '{}(Hibernate)' Server", getName());
        sf.close(); closeDs();
        if (exec instanceof ExecutorService) ((ExecutorService) exec).shutdown();
    }


    protected PersistenceUnitInfo createPersistenceUnit() {
        initDataSource();
        entityCollect();
        return new PersistenceUnitInfo() {
            @Override
            public String getPersistenceUnitName() {
                return getAttr("persistenceUnitName", getName()).toString();
            }

            @Override
            public String getPersistenceProviderClassName() {
                return HibernatePersistenceProvider.class.getName();
            }

            @Override
            public PersistenceUnitTransactionType getTransactionType() {
                return null;
            }

            @Override
            public DataSource getJtaDataSource() {
                return null;
            }

            @Override
            public DataSource getNonJtaDataSource() {
                return ds;
            }

            @Override
            public List<String> getMappingFileNames() {
                return null;
            }

            @Override
            public List<URL> getJarFileUrls() {
                return null;
            }

            @Override
            public URL getPersistenceUnitRootUrl() {
                return null;
            }

            @Override
            public List<String> getManagedClassNames() {
                return entities;
            }

            @Override
            public boolean excludeUnlistedClasses() {
                return true;
            }

            @Override
            public SharedCacheMode getSharedCacheMode() {
                return UNSPECIFIED;
            }

            @Override
            public ValidationMode getValidationMode() {
                return ValidationMode.AUTO;
            }

            @Override
            public Properties getProperties() {
                return null;
            }

            @Override
            public String getPersistenceXMLSchemaVersion() {
                return null;
            }

            @Override
            public ClassLoader getClassLoader() {
                return Hibernate.this.getClass().getClassLoader();
            }

            @Override
            public void addTransformer(ClassTransformer transformer) {}

            @Override
            public ClassLoader getNewTempClassLoader() {
                return null;
            }
        };
    }


    public Hibernate scanEntity(Class... clzs) {
        if (running.get()) throw new IllegalArgumentException("Server is running, not allow change");
        if (clzs == null || clzs.length == 0) {
            log.warn("参数错误"); return this;
        }
        for (Class clz : clzs) entityScan.add(clz);
        return this;
    }


    public Hibernate scanRepo(Class clz) {
        if (running.get()) throw new IllegalArgumentException("Server is running, not allow change");
        repoScan.add(clz);
        return this;
    }


    protected void entityCollect() {
        if (entityScan == null || entityScan.isEmpty()) return;
        log.debug("entityCollect. scan: {}", entityScan);
        for (Class tmpClz : entityScan) {
            iterateClass(tmpClz.getPackage().getName(), getClass().getClassLoader(), clz -> {
                if (clz.getAnnotation(Entity.class) != null) entities.add(clz.getName());
            });
        }
        log.trace("entities: {}", entities);
    }


    protected void repoCollect() {
        if (repoScan == null || repoScan.isEmpty()) return;
        log.debug("collect hibernate repo. scan: {}", repoScan);
        for (Class tmpClz : repoScan) {
            iterateClass(tmpClz.getPackage().getName(), getClass().getClassLoader(), clz -> {
                if (clz.getAnnotation(Repo.class) == null) return;
                Object originObj;
                try { originObj = clz.newInstance(); } catch (Exception e) { log.error(e); return; }

                iterateField(clz, f -> {
                    try {
                        if (Hibernate.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true); f.set(originObj, this);
                        } else if (EP.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true); if (f.get(originObj) == null) f.set(originObj, getEp());
                        } else if (Executor.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true); if (f.get(originObj) == null) f.set(originObj, exec);
                        } else if (EntityManagerFactory.class.isAssignableFrom(f.getType()) || SessionFactory.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true); f.set(originObj, sf);
                        } else if (TransWrapper.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true); f.set(originObj, tm);
                        }
                    } catch (Exception ex) {
                        log.error(ex);
                    }
                });

                // invoke init method
                invoke(findMethod(clz, m -> m.getAnnotation(PostConstruct.class) != null), originObj);

                // 增强 repo 对象
                Object inst = Utils.proxy(clz, (obj, method, args, proxy) -> {
                    if (method.getAnnotation(Trans.class) == null) return proxy.invoke(originObj, args);
                    else return tm.trans(() -> {
                        try { return proxy.invoke(originObj, args); } catch (Throwable t) { log.error(t); }
                        return null;
                    });
                });
                ep.addListenerSource(inst);
                log.debug("add hibernate repo object: {}", inst);
                exposeBean(inst, clz.getSimpleName()); // 暴露所有Repo出去
            });
        }
    }


    /**
     * 初始化数据源
     * @return
     */
    protected void initDataSource() {
        if (ds != null) {
            log.warn("New Datasource and close old Datasource");
            closeDs();
        }
        Map<String, String> r = (Map) ep.fire("env.ns", getName() + ".ds");
        boolean f = false;
        // druid 数据源
        try {
            Class<?> c = Class.forName("com.alibaba.druid.pool.DruidDataSourceFactory");
            Method m = c.getDeclaredMethod("createDataSource", Map.class);
            ds = (DataSource) m.invoke(null, r);
        } catch (ClassNotFoundException e) {
            // log.error(e, "datasource create error! properties: {}", ec.result);
            f = true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // dbcp2 数据源
        if (f) {
            f = false;
            try {
                Class<?> c = Class.forName("org.apache.commons.dbcp2.BasicDataSourceFactory");
                Method m = c.getDeclaredMethod("createDataSource", Properties.class);
                Properties p = new Properties(); p.putAll(r);
                ds = (DataSource) m.invoke(null, p);
            } catch (ClassNotFoundException e) {
                // log.error(e, "datasource create error! properties: {}", ec.result);
                f = true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (f) throw new RuntimeException("Not found DataSource implement class");
        log.debug("Created datasource for {} Server. {}", getName(), ds);
    }


    /**
     * 关闭 数据源
     */
    protected void closeDs() {
        try {
            Method m = ds.getClass().getDeclaredMethod("close");
            if (m != null) m.invoke(ds);
        } catch (NoSuchMethodException e) {
        } catch (Exception e) {
            log.error(e, "close datasource error");
        }
    }


    public List<String> getEntities() {
        return new LinkedList<>(entities);
    }
}
