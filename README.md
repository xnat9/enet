### 介绍
 ep.fire("event1", "参数1", "参数2")
 
 工具包含三个组件: EL(事件监听), EP(事件中心), EC(事件执行上下文)

### 安装教程
```
<dependency>
    <groupId>cn.xnatural.enet</groupId>
    <artifactId>enet-event</artifactId>
    <version>0.0.15</version>
</dependency>
```
### 事件驱动库原理

1. 事件监听器
```
// 定义监听
public class TestEP {
    // 用@EL注解标记一个方法为事件监听
    @EL(name = "hello")
    void hello() {
        System.out.println("hello ");
    }
}
```

2. 事件中心
```
// 创建一个事件中心
EP ep = new EP();
```

3. 注册事件

```
//添加监听器
ep.addListenerSource(new TestEP());
```

4. 触发事件
```
ep.fire("hello")
```

### 其他用法
```
// 带参数事件
@EL(name = "event1")
void hello(String p1, Integer p2) {
    System.out.println("p1: " + p1 + ", p2");
}

// 传参
ep.fire("event1", "参数1", 2);
// 默认填充参数为null
ep.fire("event1");
```

```
// 有返回的事件
@EL(name = "event1")
String hello(String p1) {
    return p1
}

// 触发
Object value = ep.fire("event1", "xxx");
```

```
// 事件执行完 回调
ep.fire("hello", new EC().args("参数1").completeFn((ec) -> {
    // 事件结束执行
}));
ep.fire("hello", new EC().args("参数1").completeFn((ec) -> {
    if (ec.isSuccess()) {
        // 事件成功结束后执行
    }
}));
```

```
// 动态添加监听
ep.listen("dynEvent1", () -> {
    System.out.println("执行动态事件: 无参");
});
ep.listen("dynEvent2", (p) -> {
    System.out.println("执行动态事件:  入参: " + p);
    return p;
});
ep.fire("dynEvent1");
System.out.println(ep.fire("dynEvent2", "3333"));
```

```
// 1. 同步执行: 强制
ep.fire("hello", EC.of(source).sync().args("参数1"));
// 2. 同步执行: 默认  监听器 @EL async 设置为false
```

```
// 开启debug模式
ep.fire("hello", EC.of(source).debug().args("参数1"));
```

```
// 每次触发一个事件 都会有一个 EC 对象(事件执行上下文)
ep.fire("ec"); // 自动创建EC对象
ep.fire("ec", EC.of(source).attr("key1", "value1")); // 手动创建EC对象,并设置属性
```

### 参与贡献

xnatural@msn.cn