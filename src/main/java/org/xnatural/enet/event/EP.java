package org.xnatural.enet.event;


import org.xnatural.enet.common.Log;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * event publisher 事件发布器.事件分发中心
 * TODO 事件死锁. 事件执行链
 * TODO 动态事件名, 动态事件监听
 */
public class EP {
    final   Log                         log         = Log.of(getClass());
    private Executor                    exec;
    private Map<String, List<Listener>> lsMap       = new ConcurrentHashMap<>(7);
    /**
     * 需要追踪的事件名字
     */
    private Set<String>                 trackEvents = new HashSet<>();


    public EP() {}
    public EP(Executor exec) { this.exec = exec; }


    /**
     * 触发事件
     * @param eName 事件名
     */
    public void fire(String eName) {
        fire(eName, new EC(), null);
    }


    /**
     * 触发事件
     * @param eName 事件名
     * @param completeFn 所有事件执行完后回调
     */
    public void fire(String eName, Consumer<EC> completeFn) {
        fire(eName, new EC(), completeFn);
    }


    /**
     * 触发事件
     * @param eName 事件名
     * @param ec 事件执行上下文(包括参数传递)
     */
    public void fire(String eName, EC ec) {
        fire(eName, ec, null);
    }


    /**
     * 触发事件
     * @param eName 事件名
     * @param ec 事件执行上下文(包括参数传递)
     * @param completeFn 所有事件执行完后回调
     */
    public void fire(String eName, EC ec, Consumer<EC> completeFn) {
        doPublish(eName, (ec == null ? new EC() : ec), completeFn);
    }


    /**
     * 发布事件到各个监听者
     * @param eName
     * @param ec
     * @param completeFn
     */
    protected void doPublish(String eName, EC ec, Consumer<EC> completeFn) {
        List<Listener> ls = lsMap.get(eName);
        if (ls == null || ls.isEmpty()) {
            log.trace("没有找到事件监听. name: {}", eName);
            if (completeFn != null) completeFn.accept(ec); return;
        }
        ec.willPass(ls).ep = this;
        if (trackEvents.contains(eName)) ec.track = true;
        if (ec.track) { // 是否要追踪此条事件链的执行
            ec.id = UUID.randomUUID().toString();
            log.info("开始执行事件链. name: {}, id: {}", eName, ec.id);
        }
        if (exec == null) { // 同步执行
            for (Listener l : ls) l.invoke(ec);
            if (completeFn != null) {
                completeFn.accept(ec);
                if (ec.track) log.info("结束执行事件链. name: {}, id: {}", eName, ec.id);
            }
        } else {
            // 异步和同步执行的监听器, 分开执行
            List<Listener> asyncList = new LinkedList<>();
            List<Listener> other = new LinkedList<>();
            if (ec.async == null) {
                for (Listener l : ls) {
                    if (l.async) asyncList.add(l);
                    else other.add(l);
                }
            } else {
                if (ec.async) asyncList.addAll(ls);
                else other.addAll(ls);
            }
            if (completeFn == null) {
                exec.execute(() -> asyncList.forEach(l -> l.invoke(ec)));
                other.forEach(l -> l.invoke(ec));
            } else {
                AtomicInteger i = new AtomicInteger(ls.size());
                Runnable fn = () -> { // 两个列表都执行完后才执行completeFn函数
                    if (i.get() == 0) {
                        completeFn.accept(ec);
                        if (ec.track) log.info("结束执行事件链. name: {}, id: {}", eName, ec.id);
                    }
                };
                asyncList.forEach(l -> exec.execute(() -> {
                    l.invoke(ec); i.decrementAndGet(); fn.run();
                }));
                other.forEach(l -> {
                    l.invoke(ec); i.decrementAndGet(); fn.run();
                });
            }
        }
    }


    /**
     * 添加监听源.
     * @param source
     * @return
     */
    public EP addListenerSource(Object source) {
        resolveListeners(source);
        return this;
    }


    /**
     * TODO 添加临时事件回调?
     * @param eName
     * @param fn
     * @return
     */
//    public EP when(String eName, Runnable fn) {
//        List<Listener> ls = lsMap.computeIfAbsent(eName, s -> new LinkedList<>());
//        return this;
//    }


    /**
     * 从一个对象中 解析出 所有带有 {@link EL}注解的方法 转换成监听器{@link Listener}
     * 如果带有注解 {@link EL}的方法被重写, 则用子类的方法
     * @param source
     */
    private void resolveListeners(Object source) {
        if (source == null) return;
        Class<? extends Object> c = source.getClass();
        do {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    EL a = m.getDeclaredAnnotation(EL.class);
                    if (a == null) continue;
                    Listener listener = new Listener();
                    listener.async = a.async(); listener.source = source; listener.order = a.order();
                    listener.m = m; m.setAccessible(true);
                    listener.name = parseName(a.name(), source);
                    List<Listener> ls = lsMap.computeIfAbsent(listener.name, s -> new LinkedList<>());
                    // 同一个对象源中, 不能有相同的事件监听名. 忽略并警告
                    if (ls.stream().anyMatch(l -> l.source == source && Objects.equals(l.name, m.getName()))) {
                        log.warn("同一个对象源中, 不能有相同的事件监听名. source: {}, originName: {}, name: {}", source, a.name(), listener.name);
                        continue;
                    }
                    // 同一个对象源中, 不同的监听, 方法名不能相同.
                    if (ls.stream().anyMatch(l -> l.source == source && Objects.equals(l.m.getName(), listener.m.getName()))) {
                        log.warn("同一个对象源中, 不同的监听, 方法名不能相同. source: {}, methodName: {}", source, m.getName());
                        continue;
                    }
                    if (listener.name != null) {
                        ls.add(listener); ls.sort(Comparator.comparing(o -> o.order));
                    }
                }
            } catch (Exception ex) {
                log.error(ex);
            } finally {
                c = c.getSuperclass();
            }
        } while (c != null);
    }


    private final Pattern p = Pattern.compile("\\$\\{(?<attr>\\w+)\\}");
    /**
     * 支持表达式 ${attr}.eventName, ${attr}会被替换成 对象中的属性attr的值
     * @param name
     * @param source
     * @return
     */
    private String parseName(String name, Object source) {
        Matcher ma = p.matcher(name);
        if (ma.find()) {
            String attr = ma.group("attr");
            String getName = "get" + capitalize(attr);
            Class<? extends Object> c = source.getClass();
            do {
                try {
                    Method m = c.getDeclaredMethod(getName);
                    Object v = m.invoke(source);
                    if (v == null) {
                        log.warn("解析事件名中的属性错误. name: {}, source:{} 属性: {} 值为空", name, source, attr);
                        return null;
                    }
                    return ma.replaceAll(v.toString());
                } catch (NoSuchMethodException ex) {
                    continue;
                } catch (Exception ex) {
                    log.warn(ex, "解析事件名中的属性错误. name: {}", name);
                    break;
                } finally {
                    c = c.getSuperclass();
                }
            } while (c != null);
            log.warn("解析事件名中的属性错误. name: {}, source:{} 没有此属性: {}", name, source, attr);
            return null;
        }
        return name;
    }


    /**
     * 监听器包装类
     */
    class Listener {
        Object source; Method  m;
        String name; float order;
        /**
         * 是否异步
         */
        boolean async;
        /**
         * 监听器执行体
         */
        Runnable fn;
        /**
         * 临时监听器
         */
        boolean tmp;

        void invoke(EC ec) {
            try {
                if (fn != null) fn.run();
                else {
                    if (m.getParameterCount() == 1) {
                        if (void.class.isAssignableFrom(m.getReturnType())) m.invoke(source, ec);
                        else ec.result = m.invoke(source, ec);
                    } else {
                        if (void.class.isAssignableFrom(m.getReturnType())) m.invoke(source);
                        else ec.result = m.invoke(source);
                    }
                }
                ec.passed(this);
                if (ec.track) log.info("执行事件完成. name: {}, id: {}, result: {}", name, ec.id(), ec.result);
            } catch (Throwable e) {
                ec.ex = e;
                log.error(e, "执行事件出错! name: {}, id: {}", name, ec.id());
            }
        }
    }


    private String capitalize(String str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return str;
        }

        final char firstChar = str.charAt(0);
        final char newChar = Character.toTitleCase(firstChar);
        if (firstChar == newChar) {
            // already capitalized
            return str;
        }

        char[] newChars = new char[strLen];
        newChars[0] = newChar;
        str.getChars(1,strLen, newChars, 1);
        return String.valueOf(newChars);
    }
}
