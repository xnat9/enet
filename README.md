# enet

## 介绍
 * 以一个AppContext为系统运行上下文, 各小模块服务挂载运行
 * 系统中所有的功能(各模块的功能)都以事件方法的形式发布到事件中心
 * 事件中心中包含所有事件, 能被系统中各个模块触发调用


## 安装教程
```
<dependency>
    <groupId>cn.xnatural.enet</groupId>
    <artifactId>enet-core</artifactId>
    <version>0.0.9</version>
</dependency>
<dependency>
    <groupId>cn.xnatural.enet</groupId>
    <artifactId>enet-server</artifactId>
    <version>0.0.9</version>
</dependency>
```

## 简单用例
项目模板: [enet-demo](https://gitee.com/xnat/enet/tree/master/enet-demo)

入口: [Launcher](https://gitee.com/xnat/enet/blob/master/enet-demo/src/main/java/cn/xnatural/enet/demo/Launcher.java) 类
```
public class Launcher extends ServerTpl {

    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new NettyHttp()); // http
        app.addSource(new NettyResteasy().scan(RestTpl.class)); // mvc
        app.addSource(new OpenApiDoc()); // swagger rest 接口文档
        app.addSource(new Hibernate().scanEntity(TestEntity.class).scanRepo(TestRepo.class));
        app.addSource(new EhcacheServer()); // ehcache 缓存
        app.addSource(new SchedServer()); // 定时任务服务
        app.addSource(new Remoter()); // 远程调用,集群/分布式
        app.addSource(new Launcher());
        // TODO 添加其它服务
        app.start(); // 并发启动各模块服务
    }

    @Resource
    AppContext ctx;

    // 环境配置完成后会被执行
    @EL(name = "env.configured", async = false)
    void envConfigured() {
        if (ctx.env().getBoolean("session.enabled", false)) {
            String t = ctx.env().getString("session.type", "memory");
            if ("memory".equalsIgnoreCase(t)) ctx.addSource(new MemSessionManager());
            else if ("redis".equalsIgnoreCase(t)) ctx.addSource(new RedisSessionManager());
        }
    }
}

```

## [wiki](https://gitee.com/xnat/enet/wikis/pages) 相关文章 

### [环境配置](https://gitee.com/xnat/enet/wikis/%E7%8E%AF%E5%A2%83%E9%85%8D%E7%BD%AE?sort_id=1409695)

### [事件驱动](https://gitee.com/xnat/enet/wikis/%E4%BA%8B%E4%BB%B6%E9%A9%B1%E5%8A%A8?sort_id=1409719)

### [模块说明](https://gitee.com/xnat/enet/wikis/enet-server%E6%8F%90%E4%BE%9B%E7%9A%84%E6%A8%A1%E5%9D%97%E8%AF%B4%E6%98%8E?sort_id=1409722)

## 参与贡献

xnatural@msn.cn