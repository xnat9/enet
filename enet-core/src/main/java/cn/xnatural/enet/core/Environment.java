package cn.xnatural.enet.core;

import cn.xnatural.enet.common.Log;
import cn.xnatural.enet.common.Utils;
import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.event.EP;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.xnatural.enet.common.Utils.*;

/**
 * 系统环境
 */
public class Environment {
    public final static String                           PROP_ACTIVE      = "enet.profile.active";
    protected           Log                              log              = Log.of(Environment.class);
    protected           EP                               ep;
    /**
     * 配置文件路径
     */
    protected final     List<String>                     cfgFileLocations = new LinkedList<>();
    /**
     * 配置文件名字
     */
    protected           List<String>                     cfgFileNames     = new LinkedList<>();
    /**
     * 最终汇总属性
     */
    protected final     Map<String, String>              finalAttrs       = new ConcurrentHashMap<>();
    /**
     * 运行时改变的属性, 优先级高于 {@link #finalAttrs} see: {@link #getAttr(String)}
     */
    protected final     Map<String, String>              runtimeAttrs     = new ConcurrentHashMap<>(7);
    /**
     * location 和 其对应的 所有属性
     */
    protected final     Map<String, Map<String, String>> locationSources  = new LinkedHashMap<>();
    /**
     * profile 和 其对应的 所有属性
     */
    protected final     Map<String, Map<String, String>> profileSources   = new LinkedHashMap<>();
    /**
     * 所有的profile
     */
    protected final     Set<String>                      allProfiles      = new HashSet<>(7);
    /**
     * 激活特定配置
     */
    protected final     Set<String>                      activeProfiles   = new LinkedHashSet<>(5);


    public Environment(EP ep) {
        if (ep == null) throw new IllegalArgumentException("ep must not be null");
        this.ep = ep; ep.addListenerSource(this);
        init();
    }


    /**
     * 初始化
     */
    protected void init() {
        // 按优先级添加. 先添加优先级最低, 越后边越高
        cfgFileLocations.add("classpath:/");
        cfgFileLocations.add("classpath:/config/");
        cfgFileLocations.add("file:./");
        cfgFileLocations.add("file:./config/");
        String p = System.getProperty("enet.cfgFileLocations");
        if (p != null && !p.isEmpty()) {
            for (String s : p.split(",")) {
                if (s != null && !s.trim().isEmpty()) cfgFileLocations.add(s.trim());
            }
        }

        cfgFileNames.add("application");
        p = System.getProperty("enet.cfgFileName");
        if (p != null && !p.isEmpty()) {
            for (String s : p.split(",")) {
                if (s != null && !s.trim().isEmpty()) cfgFileNames.add(s.trim());
            }
        }

        // -Denet.profile.active=pro
        if (Utils.isNotEmpty(System.getProperty(PROP_ACTIVE))) {
            allProfiles.addAll(Arrays.asList(System.getProperty(PROP_ACTIVE).split(",")));
        }
    }


    /**
     * 加载配置
     * 支持: properties 文件. 可替换类似字符串 ${attr}
     */
    protected void loadCfg() {
        log.trace("start loading configuration file");
        // 先加载默认配置文件
        for (String l : cfgFileLocations) {
            if (l == null || l.isEmpty()) continue;
            for (String n : cfgFileNames) {
                loadPropertiesFile(null, l, n);
                // loadYamlFile(null, l, n);
            }
        }
        // 遍历加载所有profile对应的配置文件
        Set<String> loadedProfiles = new HashSet<>();
        while (allProfiles.size() > loadedProfiles.size()) {
            for (String p : allProfiles) {
                if (loadedProfiles.contains(p)) continue;
                loadedProfiles.add(p); // 放在上面, 以免加载出错, 导致死循环
                for (String l : cfgFileLocations) {
                    if (l == null || l.isEmpty()) continue;
                    for (String n : cfgFileNames) {
                        loadPropertiesFile(p, l, n);
                        // loadYamlFile(p, l, n);
                    }
                }
            }
        }
        // -Denet.profile.active=pro
        if (Utils.isNotEmpty(System.getProperty(PROP_ACTIVE))) {
            activeProfiles.clear();
            activeProfiles.addAll(Arrays.asList(System.getProperty(PROP_ACTIVE).split(",")));
        }
        if (!profileSources.isEmpty()) finalAttrs.putAll(profileSources.get(null));
        for (String p : activeProfiles) {
            if (profileSources.containsKey(p)) finalAttrs.putAll(profileSources.get(p));
        }
        finalAttrs.put(PROP_ACTIVE, activeProfiles.stream().collect(Collectors.joining(",")));
        parseValue(finalAttrs, new AtomicInteger(0)); // 替换 ${attr} 值
        // 初始化日志相关
        Log.init(() -> initLog());

        log.debug("final attrs: {}", finalAttrs);
        log.trace("System attrs: {}", System.getProperties());
        log.info("The following profiles are active: {}", finalAttrs.get(PROP_ACTIVE));
        ep.fire("env.configured", EC.of(this));
    }


    /**
     * 初始化日志实现系统
     */
    protected void initLog() {
        ILoggerFactory fa = LoggerFactory.getILoggerFactory();
        // 配置 logback
        if ("ch.qos.logback.classic.LoggerContext".equals(fa.getClass().getName())) {
            try {
                // 设置logback 配置文件中的变量
                System.setProperty("PID", getPid());
                String logPath = getAttr("log.path");
                if (isNotEmpty(logPath)) System.setProperty("LOG_PATH", logPath);

                Object o = Class.forName("ch.qos.logback.classic.joran.JoranConfigurator").newInstance();
                Method m = findMethod(o.getClass(), "setContext", Class.forName("ch.qos.logback.core.Context"));
                m.invoke(o, fa);
                m = findMethod(fa.getClass(), "reset"); m.invoke(fa);
                m = findMethod(o.getClass(), "doConfigure", InputStream.class);
                boolean f = false;
                for (String p : activeProfiles) {
                    String fName = "logback-" + p + ".xml";
                    InputStream in = getClass().getClassLoader().getResourceAsStream(fName);
                    if (in != null) {
                        log.info("Configure logback file: {}", fName);
                        m.invoke(o, in); f = true;
                    }
                }
                if (!f) {// 如果没有和 profile相关的配置 就加载默认的配置文件
                    String fName = "logback.xml";
                    InputStream in = getClass().getClassLoader().getResourceAsStream(fName);
                    if (in != null) {
                        log.info("Configure logback file: {}", fName);
                        m.invoke(o, in);
                    }
                }
                // 设置日志级别
                Method setLevel = findMethod(Class.forName("ch.qos.logback.classic.Logger"), "setLevel", Class.forName("ch.qos.logback.classic.Level"));
                Method toLevel = findMethod(Class.forName("ch.qos.logback.classic.Level"), "toLevel", String.class);
                for (Map.Entry<String, String> e : groupAttr("log.level").entrySet()) {
                    setLevel.invoke(fa.getLogger(e.getKey()), toLevel.invoke(null, e.getValue()));
                }
            } catch (Exception e) {
                log.error(e);
            }
        }
    }


    /**
     * 加载 properties 文件源
     * @param profile
     * @param cfgFileLocation
     * @param cfgFileName
     */
    protected void loadPropertiesFile(String profile, String cfgFileLocation, String cfgFileName) {
        String f = cfgFileLocation + cfgFileName + (profile == null ? "" : "-" + profile) + ".properties";
        Map<String, String> r = null;
        if (cfgFileLocation.startsWith("classpath:")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(f.replace("classpath:/", ""))) {
                if (in != null) {
                    Properties p = new Properties(); p.load(in);
                    r = new LinkedHashMap(p);
                }
            } catch (Exception e) {
                log.error(e, "load cfg file '{}' error", f);
            }
        } else {
            try (InputStream in = new URL(f).openStream()) {
                Properties p = new Properties(); p.load(in);
                r = new LinkedHashMap(p);
            } catch (FileNotFoundException e) {
                log.trace("not found cfg file '{}'", f);
            } catch (Exception e) {
                log.error(e, "load cfg file '{}' error", f);
            }
        }
        if (r == null) return;
        log.trace("load cfg file '{}'\n{}", f, r);
        locationSources.put(f, r);
        profileSources.computeIfAbsent(profile, s -> new LinkedHashMap<>()).putAll(r);
        if (r.containsKey(PROP_ACTIVE)) {
            activeProfiles.clear();
            for (String p : r.get(PROP_ACTIVE).split(",")) {
                if (Utils.isNotBlank(p)) activeProfiles.add(p.trim());
            }
            allProfiles.addAll(activeProfiles);
        }
    }


    protected Pattern p = Pattern.compile("(\\$\\{(?<attr>[\\w\\._]+)\\})+");
    /**
     * 替换 值包含${attr}的字符串
     * @param attrs
     */
    protected void parseValue(Map<String, String> attrs, AtomicInteger count) {
        if (count.get() >= 3) return;
        boolean f = false; count.getAndIncrement();
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            Matcher m = p.matcher(e.getValue());
            if (!m.find()) continue;
            f = true;
            attrs.put(e.getKey(), e.getValue().replace(m.group(0), attrs.getOrDefault(m.group("attr"), "")));
        }
        if (f) parseValue(attrs, count); // 一直解析直到所有值都被替换完成;
    }


    public Integer getInteger(String key, Integer defaultValue) {
        return Utils.toInteger(getAttr(key), defaultValue);
    }


    public Long getLong(String key, Long defaultValue) {
        return Utils.toLong(getAttr(key), defaultValue);
    }


    public String getString(String key, String defaultValue) {
        String v = getAttr(key);
        return (v == null ? defaultValue : v);
    }


    public Boolean getBoolean(String name, Boolean defaultValue) {
        return Utils.toBoolean(getAttr(name), defaultValue);
    }


    /**
     * 取属性.
     * @param key
     * @return
     */
    @EL(name = "env.getAttr", async = false)
    protected String getAttr(String key) {
        String v = runtimeAttrs.get(key);
        if (v == null) v = System.getProperty(key);
        if (v == null) v = finalAttrs.get(key);
        return v;
    }


    /**
     * 用于运行时改变属性
     * @param key 属性名
     * @param value 属性值
     * @return
     */
    public Environment setAttr(String key, String value) {
        if (PROP_ACTIVE.equals(key)) throw new RuntimeException("not allow change this property '" + PROP_ACTIVE + "'");
        // 即时通知
        ep.fire("env.updateAttr", EC.of(this).args(key, value).completeFn(ec -> { if (ec.isSuccess()) runtimeAttrs.put(key, value); }));
        return this;
    }


    /**
     * 取一组属性集合.
     * 例:
     *  http.port: 8080, http-netty: 8000
     *  Map<String, String> attrs = groupAttr("http", "http-netty");
     *  System.out.println(attrs.get("port")); // 8000
     * @param keys 属性前缀
     * @return
     */
    @EL(name = "env.ns", async = false)
    public Map<String, String> groupAttr(String... keys) {
        Map<String, String> group = new HashMap<>();
        if (keys == null || keys.length < 1) return group;
        BiConsumer<String, String> fn = (k, v) -> {
            for (String key : keys) {
                if (!k.startsWith(key)) continue;
                if (k.equals(key)) group.put(k, v);
                else group.put(k.substring(key.length() + 1), v);
            }
        };

        finalAttrs.forEach(fn);
        System.getProperties().forEach((k, v) -> fn.accept(k.toString(), Objects.toString(v, null)));
        runtimeAttrs.forEach(fn);
        return group;
    }


    public Map<String, String> getFinalAttrs() {
        return new HashMap<>(finalAttrs); // 不能被外部改变
    }


    public Map<String, Map<String, String>> getLocationSources() {
        return new LinkedHashMap<>(locationSources); // 不能被外部改变
    }
}
