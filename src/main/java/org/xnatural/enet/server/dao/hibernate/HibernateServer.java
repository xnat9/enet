package org.xnatural.enet.server.dao.hibernate;

import org.hibernate.jpa.HibernatePersistenceProvider;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import javax.persistence.Entity;
import javax.persistence.EntityManagerFactory;
import javax.persistence.SharedCacheMode;
import javax.persistence.ValidationMode;
import javax.persistence.spi.ClassTransformer;
import javax.persistence.spi.PersistenceUnitInfo;
import javax.persistence.spi.PersistenceUnitTransactionType;
import javax.sql.DataSource;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

import static javax.persistence.SharedCacheMode.UNSPECIFIED;

/**
 * hibernate 服务
 * 暴露出 {@link EntityManagerFactory} 实例(所有的数据库操作入口)
 */
public class HibernateServer extends ServerTpl {
    private DataSource          ds;
    private PersistenceUnitInfo pu;
    private EntityManagerFactory       emf;
    /**
     * 实体扫描
     */
    private List<Class>         entityScan = new LinkedList<>();
    /**
     * 被管理的实体类名
     */
    private List<String>        entities   = new LinkedList<>();

    public HibernateServer() {
        setName("hibernate");
    }


    @EL(name = "sys.starting")
    protected void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getNs() + ".starting");
        // 先从核心取配置, 然后再启动
        coreEp.fire("env.ns", EC.of("ns", getNs()).sync(), (ec) -> {
            Map<String, String> m = (Map) ec.result;
            if (m.containsKey("entity-scan")) {
                for (String s : m.get("entity-scan").split(",")) {
                    if (s == null || s.trim().isEmpty()) continue;
                    try {
                        entityScan.add(Class.forName(s.trim()));
                    } catch (ClassNotFoundException e) {
                        log.error("not found class: " + s);
                    }
                }
            }
            attrs.putAll(m);
        });
        initPersistenceUnit();
        emf = new HibernatePersistenceProvider().createContainerEntityManagerFactory(pu, attrs);
        registerBean(null, emf);
        coreEp.fire(getNs() + ".started");
        log.info("Started {} Server", getName());
    }


    @EL(name = "sys.stopping")
    public void stop() {
        emf.close();
        try {
            Method m = ds.getClass().getDeclaredMethod("close");
            if (m != null) m.invoke(ds);
        } catch (NoSuchMethodException e) {
        } catch (Exception e) {
            log.error(e, "close datasource error");
        }
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    protected void initPersistenceUnit() {
        initDataSource();
        collect();
        pu = new PersistenceUnitInfo() {
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
                return HibernateServer.this.getClass().getClassLoader();
            }

            @Override
            public void addTransformer(ClassTransformer transformer) {

            }

            @Override
            public ClassLoader getNewTempClassLoader() {
                return null;
            }
        };
    }


    public HibernateServer scan(Class clz) {
        if (running.get()) throw new IllegalArgumentException("Server is running, not allow change");
        entityScan.add(clz);
        return this;
    }


    private void collect() {
        if (entityScan == null || entityScan.isEmpty()) return;
        try {
            for (Class clz : entityScan) {
                String pkg = clz.getPackage().getName();
                File pkgDir = new File(getClass().getClassLoader().getResource(pkg.replaceAll("\\.", "/")).getFile());
                for (File f : pkgDir.listFiles(f -> f.getName().endsWith(".class"))) {
                    load(pkg, f);
                }
            }
        } catch (Exception e) {
            log.error(e, "扫描Entity类出错!");
        }
    }


    private void load(String pkg, File f) throws Exception {
        if (f.isDirectory()) {
            for (File ff : f.listFiles(ff -> ff.getName().endsWith(".class"))) {
                load(pkg + "." + f.getName(), ff);
            }
        } else if (f.isFile() && f.getName().endsWith(".class")) {
            Class<?> clz = getClass().getClassLoader().loadClass(pkg + "." + f.getName().replace(".class", ""));
            if (clz.getAnnotation(Entity.class) != null) entities.add(clz.getName());
        }
    }


    /**
     * 初始化数据源
     * @return
     */
    protected void initDataSource() {
        coreEp.fire("env.ns", EC.of("ns", getNs() + ".ds").sync(), ec -> {
            boolean f = false;
            // druid 数据源
            try {
                Class<?> c = Class.forName("com.alibaba.druid.pool.DruidDataSourceFactory");
                Method m = c.getDeclaredMethod("createDataSource", Map.class);
                ds = (DataSource) m.invoke(null, (Map) ec.result);
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
                    Properties p = new Properties(); p.putAll((Map) ec.result);
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
        });
    }


    public List<String> getEntities() {
        return new LinkedList<>(entities);
    }
}
