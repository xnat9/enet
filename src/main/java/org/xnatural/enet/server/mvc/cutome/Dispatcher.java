package org.xnatural.enet.server.mvc.cutome;


import com.alibaba.fastjson.JSON;
import org.xnatural.enet.common.Log;
import org.xnatural.enet.common.Utils;
import org.xnatural.enet.server.mvc.cutome.anno.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 请求分发器
 */
class Dispatcher {
    final Log log = Log.of(getClass());

    private Executor      exec;
    private List<Handler> handlers = new LinkedList<>();
    private AntPathMatcher pathMatcher = new AntPathMatcher();


    Dispatcher(Executor exec) {
        this.exec = exec;
    }


    void dispatch(String path, String method,
                  Function<String, Object> resolver,
                  Consumer<Object> responseConsumer,
                  Object request
    ) {
        URI uri = URI.create(path);
        Map<String, String> responseHeaders = (Map<String, String>) resolver.apply("responseHeaders");
        Handler h = findHandler(uri.getPath(), method);
        if (h == null) {
            responseHeaders.put("content-type", "application/json");
            responseHeaders.put("status", "404");
            responseConsumer.accept(ApiResp.fail("not found").setErrorId("404"));
            return;
        }
        // 参数解析器
        Function<String, Object> paramResolver = (Function<String, Object>) resolver.apply("paramResolver");
        Function<String, String> requestHeaderResolver = (Function<String, String>) resolver.apply("requestHeaderResolver");

        Route r = h.m.getAnnotation(Route.class); // TODO 方法有可能是bridge, 所有参数名取到的都是 arg{0,1,2,3...}
        Object[] params = Arrays.stream(h.m.getParameters()).map(p -> {
            String n = p.getName();
            for (Annotation an : p.getAnnotations()) {
                if (an instanceof Param) n = (((Param) an).name().isEmpty() ? n : ((Param) an).name());
                else if (an instanceof RequestBody) {
                    if (!String.class.isAssignableFrom(p.getType())) JSON.parseObject((String) paramResolver.apply(n), p.getType());
                } else if (an instanceof ResponseHeaders) {
                    return responseHeaders;
                } else if (an instanceof PathVariable) {
                    n = (((PathVariable) an).name().isEmpty() ? n : ((PathVariable) an).name());
                    if (h.path.size() == 1) {
                        Map<String, String> map = pathMatcher.extractUriTemplateVariables(h.path.iterator().next(), uri.getPath());
                        if (Integer.class.isAssignableFrom(p.getType())) return Utils.toInteger(map.get(n), null);
                        else if (int.class.isAssignableFrom(p.getType())) return Utils.toInteger(map.get(n), 0);
                        else if (Long.class.isAssignableFrom(p.getType())) return Utils.toLong(map.get(n), null);
                        else if (long.class.isAssignableFrom(p.getType())) return Utils.toLong(map.get(n), 0L);
                        else return map.get(n);
                    }
                }
            }
            return paramResolver.apply(n);
        }).toArray();
        responseHeaders.put("content-type", r.produces());
        log.debug("path: {}, params: {}", path, params);
        responseConsumer.accept(h.invoke(params));
    }


    /**
     * 找到最匹配的 Handler
     * @param pPath
     * @param method
     * @return
     */
    private Handler findHandler(String pPath, String method) {
        if (pPath == null) return null;
        String lPath = (pPath.endsWith("/") ? pPath.substring(0, pPath.length() - 1) : pPath);
        List<Handler> matched = new LinkedList<>();
        for (Handler h : handlers) {
            if (h.path.contains(lPath) && h.method.contains(method)) return h;
            for (String p : h.path) {
                if (pathMatcher.match(p, lPath)) matched.add(h);
            }
        }
        if (matched.size() == 1) return matched.get(0);
        // TODO size > 1
        return null;
    }


    /**
     * 解析出对象中所有{@link Route} 的方法
     * @param source
     */
    void resolveHandler(Object source) {
        if (source == null) return;
        try {
            Route a = source.getClass().getDeclaredAnnotation(Route.class);
            if (a == null) return;
            Class<? extends Object> clz = source.getClass();
            do {
                for (Method m : clz.getDeclaredMethods()) {
                    try {
                        Route r = m.getDeclaredAnnotation(Route.class);
                        if (r == null) continue;
                        Handler h = new Handler(); h.m = m; h.target = source;

                        // 重写的只添加子类的方法
                        if (handlers.stream().anyMatch(handler -> handler.m.getName().equals(h.m.getName()))) continue;

                        addPath(h, a, r); addMethod(h, a, r); addConsumer(h, a, r);
                        handlers.add(h);
                        log.info("Mapped. {" + h.path + "," + h.method + "," + h.consumer +"} onto " + h.m);
                    } catch (Exception ex) {
                        log.warn(ex, "解析Handler错误. source: {}", source);
                    }
                }
                clz = clz.getSuperclass();
            } while (clz != null);
        } catch (Exception e) {
            log.error(e, "注册http请求handler错误.");
        }
    }


    private void addMethod(Handler h, Route a, Route r) {
        if (r.method() != null) for (String m : r.method()) {
            if ("GET".equalsIgnoreCase(m)) {
                h.method.add("GET");
            } else if ("POST".equalsIgnoreCase(m)) {
                h.method.add("POST");
            } else throw new IllegalArgumentException("不支持方法: " + m);
        }
        // 根据content-type判断是哪种method
        for (String c : r.consumes()) {
            if ("application/json".equalsIgnoreCase(c) || "multipart/form-data".equalsIgnoreCase(c)) {
                if (h.method.contains("GET")) throw new IllegalArgumentException("配置错误. consumer 和 method 总突");
                h.method.add("POST"); break;
            }
        }
        // 取类上的 Route上的method配置
        if (h.method.isEmpty() && a.method() != null) for (String m : a.method()) {
            if ("GET".equalsIgnoreCase(m)) {
                h.method.add("GET");
            } else if ("POST".equalsIgnoreCase(m)) {
                h.method.add("POST");
            } else throw new IllegalArgumentException("不支持方法: " + m);
        }
        // 默认是GET
        if (h.method.isEmpty()) h.method.add("GET");
    }


    private void addConsumer(Handler h, Route a, Route r) {
        if (r.consumes() != null) for (String c : r.consumes()) h.consumer.add(c);
        if (h.consumer.isEmpty() && a.consumes() != null) for (String c : a.consumes()) h.consumer.add(c);
        // if (h.consumer.isEmpty()) h.consumer.add("application/x-www-form-urlencoded");
    }


    private void addPath(Handler h, Route a, Route r) {
        BiFunction<String, String, Void> fn = (p1, p2) -> {
            String p = ("/" + p1 + "/" + p2).replace("//", "/");
            p = (p.endsWith("/") ? p.substring(0, p.length() - 1) : p); // 去掉末尾的 /
            if (h.path.contains(p)) throw new IllegalArgumentException("路径重复: " + p2);
            for (Handler handler : handlers) {
                if (handler.path.contains(p)) throw new IllegalArgumentException("路径重复: " + p);
            }
            h.path.add(p);
            return null;
        };
        if (a.path() == null || a.path().length < 1) {
            for (String p2 : r.path()) fn.apply("", p2);
        } else {
            for (String p1 : a.path()) {
                for (String p2 : r.path()) fn.apply(p1, p2);
            }
        }
    }


    private class Handler {
        Object   target;
        Method   m;
        Set<String> path = new HashSet<>(3);
        Set<String> method = new HashSet<>(2);
        Set<String> consumer = new HashSet<>(2);

        Object invoke(Object[] args) {
            try {
                return m.invoke(target, args);
            } catch (Exception e) {
                log.error(e);
            }
            return null;
        }
    }
}
