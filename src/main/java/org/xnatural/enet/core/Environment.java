package org.xnatural.enet.core;

import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.event.EC;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.yaml.snakeyaml.Yaml;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 系统环境
 */
public class Environment {
    public final static String                           PROP_ACTIVE      = "env.profiles.active";
    protected final     Log                              log              = Log.of(Environment.class);
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


    public Environment() {
        // 按优先级添加. 先添加优先级最低, 越后边越高
        cfgFileLocations.add("classpath:/");
        cfgFileLocations.add("classpath:/config/");
        cfgFileLocations.add("file:./");
        cfgFileLocations.add("file:./config/");
        String p = System.getProperty("enet.env.cfgFileLocations");
        if (p != null && !p.isEmpty()) {
            for (String s : p.split(",")) {
                if (s != null && !s.trim().isEmpty()) cfgFileLocations.add(s.trim());
            }
        }

        cfgFileNames.add("application");
        p = System.getProperty("enet.env.cfgFileName");
        if (p != null && !p.isEmpty()) {
            for (String s : p.split(",")) {
                if (s != null && !s.trim().isEmpty()) cfgFileNames.add(s.trim());
            }
        }
    }


    /**
     * 加载配置
     */
    protected void loadCfg() {
        log.trace("start loading cgf file");
        // 先加载默认配置文件
        for (String l : cfgFileLocations) {
            if (l == null || l.isEmpty()) continue;
            for (String n : cfgFileNames) {
                loadPropertiesFile(null, l, n); loadYamlFile(null, l, n);
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
                        loadPropertiesFile(p, l, n); // loadYamlFile(p, l, n);
                    }
                }
            }
        }
        if (Utils.isNotEmpty(System.getProperty(PROP_ACTIVE))) {
            activeProfiles.clear();
            activeProfiles.addAll(Arrays.asList(System.getProperty(PROP_ACTIVE).split(",")));
        }
        if (!profileSources.isEmpty()) finalAttrs.putAll(profileSources.get(null));
        for (String p : activeProfiles) {
            if (profileSources.containsKey(p)) finalAttrs.putAll(profileSources.get(p));
        }
        finalAttrs.put(PROP_ACTIVE, activeProfiles.stream().collect(Collectors.joining(",")));
        log.info("The following profiles are active: {}", finalAttrs.get(PROP_ACTIVE));
        ep.fire("env.configured", EC.of(this));
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
                log.error(e, "加载配置文件出错. location: {}", f);
            }
        } else {
            try (InputStream in = new URL(f).openStream()) {
                Properties p = new Properties(); p.load(in);
                r = new LinkedHashMap(p);
            } catch (FileNotFoundException e) {
                log.trace("配置文件没找到. location: {}", f);
            } catch (Exception e) {
                log.error(e, "加载配置文件出错. location: {}", f);
            }
        }
        if (r == null) return;
        log.trace("加载配置文件. location: {}\n{}", f, r);
        locationSources.put(f, r);
        profileSources.computeIfAbsent(profile, s -> new LinkedHashMap<>()).putAll(r);
        if (r.containsKey(PROP_ACTIVE)) {
            for (String p : r.get(PROP_ACTIVE).split(",")) {
                if (Utils.isNotBlank(p)) activeProfiles.add(p.trim());
            }
            allProfiles.addAll(activeProfiles);
        }
    }


    /**
     * 加载yaml/yml配置文件
     * @param profile
     * @param cfgFileLocation
     * @param cfgFileName
     */
    protected void loadYamlFile(String profile, String cfgFileLocation, String cfgFileName) {
        loadYamlFile(profile, cfgFileLocation, cfgFileName, "yaml");
        loadYamlFile(profile, cfgFileLocation, cfgFileName, "yml");
    }


    protected void loadYamlFile(String profile, String cfgFileLocation, String cfgFileName, String suffix) {
        String f = cfgFileLocation + cfgFileName + (profile == null ? "" : "-" + profile) + "." + suffix;
        Map<String, String> r = null;
        if (cfgFileLocation.startsWith("classpath:")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(f.replace("classpath:/", ""))) {
                if (in != null) {
                    r = new LinkedHashMap<>();
                    for (Object o : new Yaml().loadAll(in)) {
                        Map<String, Object> m = (Map) o;
                        Map<String, String> t = flattenMap(m, null);
                        if (t != null) r.putAll(t);
                    };
                }
            } catch (Exception e) {
                log.error(e, "加载配置文件出错. location: {}", f);
            }
        } else {
            try (InputStream in = new URL(f).openStream()) {
                r = new LinkedHashMap<>();
                for (Object o : new Yaml().loadAll(in)) {
                    Map<String, Object> m = (Map) o;
                    Map<String, String> t = flattenMap(m, null);
                    if (t != null) r.putAll(t);
                };
            }  catch (FileNotFoundException e) {
                log.trace("配置文件没找到. location: {}", f);
            } catch (Exception e) {
                log.error(e, "加载配置文件出错. location: {}", f);
            }
        }
        if (r == null) return;
        log.trace("加载配置文件. location: {}\n{}", f, r);
        locationSources.put(f, r);
        profileSources.computeIfAbsent(profile, s -> new LinkedHashMap<>()).putAll(r);
        if (r.containsKey(PROP_ACTIVE)) {
            for (String p : r.get(PROP_ACTIVE).split(",")) {
                if (Utils.isNotBlank(p)) activeProfiles.add(p.trim());
            }
            allProfiles.addAll(activeProfiles);
        }
    }


    /**
     * 扁平化 yaml 文件的 map
     * @param m
     * @param pKey
     * @return
     */
    protected Map<String, String> flattenMap(Map<String, Object> m, String pKey) {
        if (m == null) return null;
        Map<String, String> r = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v == null) return;
            if (Map.class.isAssignableFrom(v.getClass())) {
                //  TODO 尾调用
                Map<String, String> t = flattenMap((Map<String, Object>) v, (pKey == null ? "" : pKey + ".") + k);
                if (t != null) r.putAll(t);
            } else r.put((pKey == null ? "" : pKey + ".") + k, v.toString());
        });
        return r;
    }


    public Integer getInteger(String key, Integer defaultValue) {
        String v = getAttr(key);
        return Utils.toInteger(v, defaultValue);
    }


    public Long getLong(String key, Long defaultValue) {
        String v = getAttr(key);
        return Utils.toLong(v, defaultValue);
    }


    public String getString(String key, String defaultValue) {
        String v = getAttr(key);
        return (v == null ? defaultValue : v);
    }


    public Boolean getBoolean(String name, Boolean defaultValue) {
        String v = getAttr(name);
        return Utils.toBoolean(v, defaultValue);
    }


    @EL(name = "env.getAttr", async = false)
    protected String getAttr(String key) {
        String v = runtimeAttrs.get(key);
        if (v == null) v = finalAttrs.get(key);
        return v;
    }


    /**
     * 取一个命令空间下的所有属性集合
     * @param ns
     * @return
     */
    @EL(name = "env.ns", async = false)
    protected Map<String, String> ns(String ns) {
        return getGroupAttr(ns);
    }


    /**
     * 用于运行时改变属性
     * @param key
     * @param value
     * @return
     */
    public Environment setAttr(String key, String value) {
        if (PROP_ACTIVE.equals(key)) throw new IllegalArgumentException("not allow change this property '" + PROP_ACTIVE + "'");
        ep.fire(
                "env.updateAttr",
                EC.of(this).args(key, value),
                ec -> {
                    if (ec.isSuccess()) runtimeAttrs.put(key, value);
                }
        );
        return this;
    }


    /**
     * 取一组属性集合
     * @param key
     * @return
     */
    public Map<String, String> getGroupAttr(String key) {
        Map<String, String> group = new HashMap<>();
        finalAttrs.forEach((k, v) -> {
            if (!k.startsWith(key)) return;
            if (k.equals(key)) group.put(k, v);
            else group.put(k.substring(key.length() + 1), v);
        });
        runtimeAttrs.forEach((k, v) -> {
            if (!k.startsWith(key)) return;
            if (k.equals(key)) group.put(k, v);
            else group.put(k.substring(key.length() + 1), v);
        });
        return group;
    }


    Environment setEp(EP ep) {
        this.ep = ep;
        ep.addListenerSource(this);
        return this;
    }


    public Map<String, String> getFinalAttrs() {
        return new HashMap<>(finalAttrs); // 不能被外部改变
    }


    public Map<String, Map<String, String>> getLocationSources() {
        return new LinkedHashMap<>(locationSources); // 不能被外部改变
    }
}
