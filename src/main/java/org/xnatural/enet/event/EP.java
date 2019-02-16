package org.xnatural.enet.event;


import org.xnatural.enet.common.Log;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * event publisher 事件发布器.事件分发中心
 * TODO 事件死锁. 事件执行链
 */
public class EP {
    protected final Log                         log         = Log.of(EP.class);
    protected       Executor                    exec;
    protected       Map<String, List<Listener>> lsMap       = new ConcurrentHashMap<>(7);
    /**
     * 需要追踪的事件名字
     */
    protected final Set<String>                 trackEvents = new HashSet<>(7);


    public EP() {}
    public EP(Executor exec) { this.exec = exec; }


    /**
     * {@link #fire(String, EC, Consumer)}
     */
    public Object fire(String eName) {
        return fire(eName, new EC(), null);
    }


    /**
     * {@link #fire(String, EC, Consumer)}
     * @param eName 事件名
     * @param args 监听器方法的参数列表
     * @return
     */
    public Object fire(String eName, Object...args) {
        return fire(eName, new EC().args(args), null);
    }


    /**
     * 触发事件
     * @param eName 事件名
     * @param completeFn 所有事件执行完后回调
     */
    public Object fire(String eName, Consumer<EC> completeFn) {
        return fire(eName, new EC(), completeFn);
    }


    /**
     *
     * @param eName
     * @param completeFn
     * @param args 监听器方法的参数列表
     * @return
     */
    public Object fire(String eName, Consumer<EC> completeFn, Object...args) {
        return fire(eName, new EC().args(args), completeFn);
    }


    /**
     * 触发事件
     * @param eName 事件名
     * @param ec 事件执行上下文(包括参数传递)
     */
    public Object fire(String eName, EC ec) {
        return fire(eName, ec, null);
    }


    /**
     * 触发事件
     * @param eName 事件名
     * @param ec 事件执行上下文(包括参数传递)
     * @param completeFn 所有事件执行完后回调
     * @return
     */
    public Object fire(String eName, EC ec, Consumer<EC> completeFn) {
        return doPublish(eName, (ec == null ? new EC() : ec), completeFn);
    }


    /**
     * 发布事件到各个监听者
     * @param eName 事件名
     * @param ec {@link EC} 事件执行过程上下文
     * @param completeFn 所有事件执行完后回调
     * @return Note: 取返回值时, 要注意是同步执行还是异步执行
     */
    protected Object doPublish(String eName, EC ec, Consumer<EC> completeFn) {
        List<Listener> ls = lsMap.get(eName);
        if (ls == null || ls.isEmpty()) {
            log.trace("not found listener for event name: {}", eName);
            if (completeFn != null) completeFn.accept(ec); return ec.result;
        }
        ec.willPass(ls).ep = this;
        if (trackEvents.contains(eName) || log.isTraceEnabled()) ec.track = true;
        if (ec.track) { // 是否要追踪此条事件链的执行
            ec.id = UUID.randomUUID().toString();
            log.info("starting executing listener chain for event name '{}'. id: {}, event source: {}", eName, ec.id, ec.source());
        }
        if (exec == null) { // 只能同步执行
            for (Listener l : ls) l.invoke(ec);
            if (ec.track) log.info("end executing listener chain for event name '{}'. id: {}, result: {}", eName, ec.id, ec.result);
            if (completeFn != null) completeFn.accept(ec);
        } else {
            // 异步和同步执行的监听器, 分开执行
            List<Listener> asyncLs = new LinkedList<>(); // 异步执行的监听器
            List<Listener> syncLs = new LinkedList<>(); // 同步执行的监听器
            if (ec.async == null) {
                for (Listener l : ls) {
                    if (l.async) asyncLs.add(l);
                    else syncLs.add(l);
                }
            } else {
                if (ec.async) asyncLs.addAll(ls);
                else syncLs.addAll(ls);
            }

            if (completeFn == null) {
                if (ec.track) {
                    AtomicInteger i = new AtomicInteger(ls.size());
                    AtomicBoolean f = new AtomicBoolean(false); // 防止被执行多遍
                    Runnable fn = () -> {
                        if (i.get() == 0 && f.compareAndSet(false, true)) {
                            if (ec.track) log.info("end executing listener chain for event name '{}'. id: {}, result: {}", eName, ec.id, ec.result);
                        }
                    };
                    asyncLs.forEach(l -> exec.execute(() -> {l.invoke(ec); i.decrementAndGet(); fn.run();}));
                    syncLs.forEach(l -> {l.invoke(ec); i.decrementAndGet(); fn.run();});
                } else {
                    asyncLs.forEach(l -> exec.execute(() -> l.invoke(ec)));
                    syncLs.forEach(l -> l.invoke(ec));
                }
            } else {
                AtomicInteger i = new AtomicInteger(ls.size());
                AtomicBoolean f = new AtomicBoolean(false); // 防止被执行多遍
                Runnable fn = () -> { // 两个列表都执行完后才执行completeFn函数
                    if (i.get() == 0 && f.compareAndSet(false, true)) {
                        if (ec.track) log.info("end executing listener chain for event name '{}'. id: {}, result: {}", eName, ec.id, ec.result);
                        completeFn.accept(ec);
                    }
                };
                asyncLs.forEach(l -> exec.execute(() -> {l.invoke(ec); i.decrementAndGet(); fn.run();}));
                syncLs.forEach(l -> {l.invoke(ec); i.decrementAndGet(); fn.run();});
            }
        }
        return ec.result;
    }


    /**
     * 添加监听源.
     * @param source
     * @return
     */
    public EP addListenerSource(Object source) {
        resolve(source);
        return this;
    }


    /**
     * 设置某个事件需要追踪执行
     * @param eNames
     * @return
     */
    public EP addTrackEvent(String... eNames) {
        if (eNames == null) return this;
        for (String n : eNames) if (n != null && !n.trim().isEmpty()) trackEvents.add(n.trim());
        return this;
    }


    /**
     * 删除事件追踪
     * @param eNames
     * @return
     */
    public EP delTrackEvent(String... eNames) {
        if (eNames == null) return this;
        for (String n : eNames) if (n != null && !n.trim().isEmpty()) trackEvents.remove(n);
        return this;
    }


    /**
     * 从一个对象中 解析出 所有带有 {@link EL}注解的方法 转换成监听器{@link Listener}
     * 如果带有注解 {@link EL}的方法被重写, 则用子类的方法
     * @param source
     */
    protected void resolve(final Object source) {
        if (source == null) return;
        Class<? extends Object> c = source.getClass();
        do {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    EL el = m.getDeclaredAnnotation(EL.class);
                    if (el == null) continue;
                    for (String n : el.name()) {
                        Listener listener = new Listener();
                        listener.async = el.async(); listener.source = source; listener.order = el.order();
                        listener.m = m; m.setAccessible(true); listener.name = parseName(n, source);
                        if (listener.name == null) continue;

                        List<Listener> ls = lsMap.computeIfAbsent(listener.name, s -> new LinkedList<>());
                        // 同一个对象源中, 不能有相同的事件监听名. 忽略
                        if (ls.stream().anyMatch(l -> l.source == source && Objects.equals(l.name, listener.name))) {
                            log.trace("Exist listener. name: {}, source: {}", n, listener.source);
                            continue;
                        }
                        // 同一个对象源中, 不同的监听, 方法名不能相同.
                        if (ls.stream().anyMatch(l -> l.source == source && Objects.equals(l.m.getName(), listener.m.getName()))) {
                            log.warn("Same source same method name only one listener. source: {}, methodName: {}", source, m.getName());
                            continue;
                        }

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


    protected Pattern p = Pattern.compile("\\$\\{(?<attr>\\w+)\\}");
    /**
     * 支持表达式 ${attr}.eventName, ${attr}会被替换成 对象中的属性attr的值
     * @param name
     * @param source
     * @return
     */
    protected String parseName(String name, Object source) {
        Matcher ma = p.matcher(name);
        if (!ma.find()) return name;
        String attr = ma.group("attr");
        String getName = "get" + capitalize(attr);
        Class<? extends Object> c = source.getClass();
        do {
            try {
                Method m = c.getDeclaredMethod(getName);
                if (m == null) break;
                Object v = m.invoke(source);
                if (v == null || v.toString().isEmpty()) {
                    log.warn("Parse event name '{}' error. Get property '{}' is empty from '{}'.", name, attr, source);
                    return null;
                }
                return ma.replaceAll(v.toString());
            } catch (NoSuchMethodException ex) {
            } catch (Exception ex) {
                log.warn(ex, "Parse event name '{}' error. source: {}", name, source);
                break;
            } finally {
                c = c.getSuperclass();
            }
        } while (c != null);
        log.warn("Parse event name '{}'. Not found property '{}' from {} ", name, attr, source);
        return null;
    }


    /**
     * 监听器包装类
     */
    protected class Listener {
        //  监听执行体. (一个方法).
        protected Object source; Method  m;
        /**
         * 和 {@link #m} 只存在一个
         * 监听器执行体. (一段执行逻辑)
         */
        protected Runnable fn;
        /**
         * 监听的事件名
         */
        protected String name;
        /**
         * 排序. 一个事件对应多个监听器时生效. {@link #doPublish(String, EC, Consumer)}
         */
        protected float order;
        /**
         * 是否异步
         */
        protected boolean async;


        protected void invoke(EC ec) {
            try {
                if (fn != null) fn.run();
                else {
                    Object r;
                    if (m.getParameterCount() == 0) r = m.invoke(source);
                    else if (m.getParameterCount() == 1) {
                        if (EC.class.isAssignableFrom(m.getParameterTypes()[0])) r = m.invoke(source, ec);
                        else if (EP.class.isAssignableFrom(m.getParameterTypes()[0])) r = m.invoke(source, EP.this);
                        else r = m.invoke(source, ec.args);
                    } else { // m.getParameterCount() > 1 的情况
                        Object[] args = new Object[m.getParameterCount()]; // 参数传少了, 补null
                        if (EC.class.isAssignableFrom(m.getParameterTypes()[0])) {
                            args[0] = ec;
                            if (ec.args != null) {
                                for (int i = 1; i <= ec.args.length; i++) args[i] = ec.args[i-1];
                            }
                        } else if (ec.args != null) {
                            for (int i = 0; i < ec.args.length; i++) args[i] = ec.args[i];
                        }
                        r = m.invoke(source, args);
                    }
                    if (!void.class.isAssignableFrom(m.getReturnType())) ec.result = r;
                }
                ec.passed(this);
                if (ec.track) log.info("Passed listener of event '{}'. method: {}, id: {}, result: {}",
                        name, (m == null ? "" : source.getClass().getSimpleName() + "." + m.getName()),
                        ec.id, ec.result
                );
            } catch (Throwable e) {
                ec.ex = e;
                log.error(e, "Listener execute error! name: {}, id: {}, method: {}, event source: {}",
                        name, ec.id, (m == null ? "" : source.getClass().getSimpleName() + "." + m.getName()),
                        ec.source().getClass().getSimpleName()
                );
            }
        }
    }


    protected String capitalize(String str) {
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
