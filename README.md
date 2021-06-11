# 介绍
响应式事件驱动工具

ep.fire("event1", "参数1", "参数2")

工具包含三个组件: EL(事件监听), EP(事件中心), EC(事件执行上下文)

# 安装教程
```xml
<dependency>
    <groupId>cn.xnatural.enet</groupId>
    <artifactId>enet-event</artifactId>
    <version>0.0.18</version>
</dependency>
```
# 事件驱动库原理

1. 事件监听器
```java
// 定义监听
public class TestEP {
    // 用@EL注解标记一个方法为事件监听
    @EL(name = "hello")
    void hello() {
        System.out.println("hello world");
    }
}
```

2. 事件中心
```java
// 方法1: 创建一个事件中心
final EP ep = new EP();
```

```java
// 方法2: 创建一个事件中心
final EP ep = new EP(Executors.newFixedThreadPool(2, new ThreadFactory() {
        final AtomicInteger i = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ep-" + i.incrementAndGet());
        }
}));
```

3. 注册事件

```java
//添加监听器
ep.addListenerSource(new TestEP());
```

4. 触发事件
```java
ep.fire("hello"); // 打印: hello world
```

# 其他用法

## 带参数事件
```java
@EL(name = "event1")
void hello(String p1, Integer p2) {
    System.out.println("p1: " + p1 + ", p2");
}

// 传参
ep.fire("event1", "参数1", 2);
// 默认填充参数为null
ep.fire("event1");
```

## 有返回的事件
```java
@EL(name = "event1")
String hello(String p1) { return p1;}

// 触发
Object value = ep.fire("event1", "xxx");
```

## 事件执行完回调
```java
ep.fire("hello", new EC().args("参数1").completeFn((ec) -> {
    // 事件结束执行
}));
ep.fire("hello", new EC().args("参数1").completeFn((ec) -> {
    if (ec.isSuccess()) {
        // 事件成功结束后执行
    }
}));
```

## 动态监听器
```java
//1. 添加
ep.listen("dynEvent1", () -> {
    System.out.println("执行动态事件: 无参");
});
// 带参数
ep.listen("dynEvent2", (p) -> {
    System.out.println("执行动态事件:  入参: " + p);
    return p;
});
ep.fire("dynEvent1");
System.out.println(ep.fire("dynEvent2", "3333"));

//2. 删除
ep.removeEvent("dynEvent1");
```

## 同步异步
### 同步执行(默认)
```java
// 1. 监听器设置同步(默认就是同步)
@EL(name = "sync", async = false)
String syncListener(){ return "xxx";}
```
```
// 2. 同步执行: 强制
Object result = ep.fire("sync",EC.of(source).sync());
```
### 异步执行
```java
// 1. 监听器设置异步
@EL(name = "async", async = true)
String asyncListener(){ return "oo"; }
```
```
// 2. 异步执行: 强制
ep.fire("async", EC.of(source).async(true));
```
```java
// 3. 接收异步结果
ep.fire("async", EC.of(source).async(true).completeFn(ec -> {
    // 异步结果: ec.result
}));
```

## 执行顺序
> 同相事件名的多个监听器的执行顺序: 由@EL order 指定(默认: 0)
>> 从小到大执行, 越小越先执行
> + 先按优先级顺序执行所有同步监听器
> + 再按优先级分组执行所有异步监听器
```java
@EL(name = "order")
void order1() {log.info("同步 order1");}

@EL(name = "order", async = true)
void order2() {log.info("异步 order2");}

@EL(name = "order", async = true)
void order22() {log.info("异步 order22");}

@EL(name = "order", async = true)
void order222() {log.info("异步 order222");}

@EL(name = "order", async = true, order = 1f)
void order3() {log.info("异步 order3");}
```
> 所以会先执行 order1, 结束后再并发执行(order2,order22,order22), 结束后再执行 order3

## debug模式
日志打印事件执行前后详情
```java
// 方法1
ep.fire("hello", EC.of(this).debug().args("参数1"));
```
```
// 方法2
ep.addTrackEvent("hello");
```

## 事件执行上下文
```java
@EL(name = "ec")
String ec(EC ec, String p1) {
    return p1 + ec.getAttr("key1");
}

// 每次触发一个事件 都会有一个 EC 对象(事件执行上下文)
ep.fire("ec"); // 自动创建EC对象
ep.fire("ec", EC.of(this).args("xx").attr("key1", "oo")); // 手动创建EC对象,并设置属性. 返回: xxoo
```
# [远程事件](https://gitee.com/xnat/remoter)
# 参与贡献

xnatural@msn.cn