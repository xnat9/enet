package cn.xnatural.enet.event;


import cn.xnatural.enet.common.Log;
import cn.xnatural.enet.common.Utils;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    protected Log                         log;
    protected Executor                    exec;
    /**
     * 事件名 -> 监听器
     */
    protected Map<String, List<Listener>> lsMap;
    /**
     * 需要追踪的事件名字
     */
    protected Set<String>                 trackEvents;


    public EP() { init(null); }
    public EP(Executor exec) { init(exec); }


    /**
     * 初始化
     * @param exec
     */
    protected void init(Executor exec) {
        this.exec = exec;
        log = Log.of(EP.class);
        lsMap = new ConcurrentHashMap<>(7);
        trackEvents = new HashSet<>(7);
    }


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
        List<Listener> ls = lsMap.get(eName); // 获取需要执行的监听器
        if (ls == null || ls.isEmpty()) {
            log.trace("Not found listener for event name: {}", eName);
            if (completeFn != null) completeFn.accept(ec);
            return ec.result;
        }
        ec.willPass(ls).ep = this;
        if (trackEvents.contains(eName) || log.isTraceEnabled()) ec.track = true;
        if (ec.track) { // 是否要追踪此条事件链的执行
            ec.id = UUID.randomUUID().toString();
            log.info("Starting executing listener chain for event name '{}'. id: {}, event source: {}", eName, ec.id, ec.source());
        }
        if (exec == null) { // 只能同步执行
            for (Listener l : ls) l.invoke(ec);
            if (ec.track) log.info("End executing listener chain for event name '{}'. id: {}, result: {}", eName, ec.id, ec.result);
            if (completeFn != null) completeFn.accept(ec);
        } else {
            // 异步, 同步执行的监听器, 分开执行
            List<Listener> asyncLs = new LinkedList<>(); // 异步执行的监听器
            List<Listener> syncLs = new LinkedList<>(); // 同步执行的监听器
            if (ec.async == null) {
                for (Listener l : ls) {
                    if (l.async) asyncLs.add(l);
                    else syncLs.add(l);
                }
            } else {
                if (ec.async) asyncLs.addAll(ls); // 强制全部异步执行
                else syncLs.addAll(ls); // 强制全部同步执行
            }

            if (completeFn == null) {
                if (ec.track) {
                    AtomicInteger i = new AtomicInteger(ls.size());
                    AtomicBoolean f = new AtomicBoolean(false); // 防止被执行多遍
                    Runnable fn = () -> {
                        if (i.get() == 0 && f.compareAndSet(false, true)) {
                            if (ec.track) log.info("End executing listener chain for event name '{}'. id: {}, result: {}", eName, ec.id, ec.result);
                        }
                    };
                    syncLs.forEach(l -> {l.invoke(ec); i.decrementAndGet(); fn.run();});
                    asyncLs.forEach(l -> exec.execute(() -> {l.invoke(ec); i.decrementAndGet(); fn.run();}));
                } else {
                    syncLs.forEach(l -> l.invoke(ec));
                    asyncLs.forEach(l -> exec.execute(() -> l.invoke(ec)));
                }
            } else {
                AtomicInteger i = new AtomicInteger(ls.size());
                AtomicBoolean f = new AtomicBoolean(false); // 防止被执行多遍
                Runnable fn = () -> { // 两个列表都执行完后才执行completeFn函数
                    if (i.get() == 0 && f.compareAndSet(false, true)) {
                        if (ec.track) log.info("End executing listener chain for event name '{}'. id: {}, result: {}", eName, ec.id, ec.result);
                        completeFn.accept(ec);
                    }
                };
                syncLs.forEach(l -> {l.invoke(ec); i.decrementAndGet(); fn.run();});
                asyncLs.forEach(l -> exec.execute(() -> {l.invoke(ec); i.decrementAndGet(); fn.run();}));
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
     * 删除指定对象源中的事件监听
     * @param eName 事件名
     * @param source 监听器对象源
     * @return this
     */
    public EP removeEvent(String eName, Object source) {
        if (source == null) lsMap.remove(eName);
        else {
            for (Iterator<Listener> it = lsMap.get(eName).iterator(); it.hasNext(); ) {
                Listener l = it.next();
                if (l.source == source) it.remove();
            }
        };
        return this;
    }


    /**
     * 从一个对象中 解析出 所有带有 {@link EL}注解的方法 转换成监听器{@link Listener}
     * 如果带有注解 {@link EL}的方法被重写, 则用子类的方法
     * @param source
     */
    protected void resolve(final Object source) {
        if (source == null) return;
        Utils.iterateMethod(source.getClass(), m -> { // 查找包含@EL注解的方法
            EL el = m.getDeclaredAnnotation(EL.class);
            if (el == null) return;
            for (String n : el.name()) {
                Listener listener = new Listener();
                listener.async = el.async(); listener.source = source; listener.order = el.order();
                listener.m = m; m.setAccessible(true); listener.name = parseName(n, source);
                if (listener.name == null) continue;

                List<Listener> ls = lsMap.computeIfAbsent(listener.name, s -> new LinkedList<>());
                // 同一个对象源中, 不能有相同的事件监听名. 忽略
                if (ls.stream().anyMatch(l -> l.source == source && Objects.equals(l.name, listener.name))) {
                    log.warn("Exist listener. name: {}, source: {}", n, listener.source);
                    continue;
                }
                // 同一个对象源中, 不同的监听, 方法名不能相同.
                if (ls.stream().anyMatch(l -> l.source == source && Objects.equals(l.m.getName(), listener.m.getName()))) {
                    log.warn("Same source same method name only one listener. source: {}, methodName: {}", source, m.getName());
                    continue;
                }
                log.debug("Add listener [name: {}, source: {}, method: {}, async: {}, order: {}]", listener.name, source, m.getName(), listener.async, listener.order);
                ls.add(listener); ls.sort(Comparator.comparing(o -> o.order));
            }
        });
    }


    protected Pattern p = Pattern.compile("\\$\\{(?<attr>\\w+)\\}");
    /**
     * 支持表达式 ${attr}.eventName, ${attr}会被替换成 对象中的属性attr的值
     * @param name
     * @param source
     * @return
     */
    protected String parseName(String name, Object source) {
        Matcher matcher = p.matcher(name);
        if (!matcher.find()) return name;
        String attr = matcher.group("attr");
        // 查找属性对应的get方法
        Method m = Utils.findMethod(source.getClass(), mm -> {
            if (Modifier.isPublic(mm.getModifiers()) &&
                mm.getParameterCount() == 0 && // 参数个数为0
                String.class.equals(mm.getReturnType()) && // 返回String类型
                ("get" + (attr.substring(0, 1).toUpperCase() + attr.substring(1))).equals(mm.getName()) // get方法名相同
            ) return true;
            return false;
        });
        if (m != null) {
            Object v = Utils.invoke(m, source);
            if (v == null || v.toString().isEmpty()) {
                log.warn("Parse event name '{}' error. Get property '{}' is empty from '{}'.", name, attr, source);
                return null;
            }
            return matcher.replaceAll(v.toString());
        }
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

        // 调用此监听器
        protected void invoke(EC ec) {
            try {
                if (fn != null) fn.run();
                else {
                    Object r = null;
                    if (m.getParameterCount() == 0) r = m.invoke(source); // 没有参数的情况.直接调用
                    else if (m.getParameterCount() == 1) { // 1个参数的情况
                        Class<?> t = m.getParameterTypes()[0];
                        if (EC.class.isAssignableFrom(t)) r = m.invoke(source, ec);
                        else if (t.isArray()) { // 如果是数组需要转下类型
                            Object arr = Array.newInstance(t.getComponentType(), ec.args.length);
                            for (int i = 0; i < ec.args.length; i++) {
                                Array.set(arr, i, t.getComponentType().cast(ec.args[i]));
                            }
                            r = m.invoke(source, arr);
                        }
                        else r = m.invoke(source, ec.args);
                    } else { // 参数个数多于1个的情况
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
                ec.ex = e.getCause() == null ? e : e.getCause();
                log.error(ec.ex, "Listener invoke error! name: {}, id: {}, method: {}, event source: {}",
                    name, ec.id,
                    (m == null ? "" : source.getClass().getSimpleName() + "." + m.getName()),
                    (ec.source() == null ? null : ec.source().getClass().getSimpleName())
                );
            }
        }
    }
}
