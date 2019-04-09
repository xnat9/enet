# enet

## 介绍
 以AppContext为系统运行上下文, 各小模块服务挂载运行
 
 * 简单: 各模块不存在编译/启动上的依赖. 最大程度的解耦. 这样各个模块的界限清楚明了
 * 稳定: 各种模块可独立编译,运行,测试. 模块自治
 * 灵活: 可自定义模块, 可随意按需加入到系统中
 * 高效: 事件网络到各个模块异步执行任务充分利用线程资源
    ### 系统运行上下文 AppContext
        1. 环境系统(Environment). 为系统本身和各模块提供环境配置
        2. 事件中心(EP). 包含事件的注册(addListenerSource),发布(doPublish),执行(invoke)
        3. 执行器(线程池). 系统的所有执行最终会扔到这个线程池
        4. 模块管理


## 安装教程
```
<dependency>
    <groupId>org.xnatural.enet</groupId>
    <artifactId>enet-core</artifactId>
    <version>0.0.8</version>
</dependency>
<dependency>
    <groupId>org.xnatural.enet</groupId>
    <artifactId>enet-server</artifactId>
    <version>0.0.8</version>
</dependency>
```

## 简单用例
可参照模块: enet-test

入口: [Launcher](https://gitee.com/xnat/enet/blob/master/enet-test/src/main/java/org/xnatural/enet/test/Launcher.java) 类
```
public class Launcher extends ServerTpl {

    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new NettyHttp());
        app.addSource(new NettyResteasy().scan(RestTpl.class));
        app.addSource(new OpenApiDoc());
        app.addSource(new Hibernate().scanEntity(TestEntity.class).scanRepo(TestRepo.class));
        app.addSource(new Launcher(app));
        // app.addSource(new MongoClient("localhost", 27017));
        // TODO 添加其它服务()
        app.start(); // 并发启动各模块服务
    }

    AppContext ctx;
    public Launcher(AppContext ctx) {
        setName("launcher"); this.ctx = ctx;
    }

    // 环境配置完成后执行
    @EL(name = "env.configured", async = false)
    private void envConfigured() {
        if (ctx.env().getBoolean("session.enabled", true)) {
            String t = ctx.env().getString("session.type", "memory");
            // 根据配置来启动用什么session管理
            if ("memory".equalsIgnoreCase(t)) ctx.addSource(new MemSessionManager());
            else if ("redis".equalsIgnoreCase(t)) ctx.addSource(new RedisSessionManager());
        }
    }
}

```

## [wiki](https://gitee.com/xnat/enet/wikis/pages) 相关文章 

### [环境配置](https://gitee.com/xnat/enet/wikis/%E7%8E%AF%E5%A2%83%E9%85%8D%E7%BD%AE?sort_id=1409695)

### [事件驱动](https://gitee.com/xnat/enet/wikis/%E4%BA%8B%E4%BB%B6%E9%A9%B1%E5%8A%A8?sort_id=1409719)

### [事件说明](https://gitee.com/xnat/enet/wikis/%E4%BA%8B%E4%BB%B6%E8%AF%B4%E6%98%8E?sort_id=1409714)

### [enet-server提供的模块说明](https://gitee.com/xnat/enet/wikis/enet-server%E6%8F%90%E4%BE%9B%E7%9A%84%E6%A8%A1%E5%9D%97%E8%AF%B4%E6%98%8E?sort_id=1409722)

### [eureka 注册例子](https://gitee.com/xnat/enet/wikis/eureka%E6%B3%A8%E5%86%8C?sort_id=1400954)


## 参与贡献

xnatural@msn.cn