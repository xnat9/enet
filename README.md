# enet

## 介绍
 以AppContext为系统运行上下文, 各小模块服务挂载运行
 
 * 简单: 各模块不存在编译/启动上的依赖. 最大程度的解耦. 这样各个模块的界限清楚明了
 * 稳定: 各种模块可独立编译,运行,测试. 模块自治
 * 灵活: 可自定义模块, 可随意按需加入到系统中
 * 高效: 事件网络到各个模块异步执行任务充分利用线程资源
    ### 系统运行上下文 AppContext
        1. 环境系统(Environment). 为系统本身和各模块提供环境配置
        2. 事件中心(EP)
            * 事件中心包含了整个系统中的所有监听器, 每个监听器代表一个功能点
            * 每个模块被加入到系统中时, 系统会自动搜索模块所提供的监听器(即: 有注解@EL的方法)
            * 各模块的功能调用, 只需要触发事件(@EL提供的)就行. coreEp.fire方法
        3. 执行器(线程池). 系统的所有执行最终会扔到这个线程池
        4. 各模块引用


## 安装教程
```
<dependency>
    <groupId>org.xnatural.enet</groupId>
    <artifactId>enet-core</artifactId>
    <version>0.0.6</version>
</dependency>
<dependency>
    <groupId>org.xnatural.enet</groupId>
    <artifactId>enet-server</artifactId>
    <version>0.0.6</version>
</dependency>
```

## 完整例子
```
public class TestApp extends ServerTpl {

    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new Netty4HttpServer());
        app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
        app.addSource(new MViewServer());
        app.addSource(new SwaggerServer());
        app.addSource(new HibernateServer().scanEntity(TestEntity.class).scanRepo(TestRepo.class));
        app.addSource(new EhcacheServer());
        app.addSource(new SchedServer());
        app.addSource(new TestApp(app));
        app.start();
    }


    AppContext ctx;
    public TestApp(AppContext ctx) {
        setName("app");
        this.ctx = ctx;
    }


    /**
     * 环境配置完成后执行
     * @param ec
     */
    @EL(name = "env.configured", async = false)
    private void envConfigured(EC ec) {
        Environment env = ((Environment) ec.source());
        String t = env.getString("session.type", "memory");
        // 动态启动服务
        if ("memory".equalsIgnoreCase(t)) ec.ep().fire("sys.addSource", new MemSessionManager());
    }
}

```

## 事件说明
    sys.starting: 系统启动. 各服务接收到启动事件后各自并行启动
    sys.started: 系统启动完成. 即所有服务都已启动完成
    sys.stopping: 系统关闭通知.
    env.configured: 环境已配置完成. 即各配置属性已可取得
    env.updateAttr: 有属性改变. 可在运行时改变某属性


## enet-server内置可用多模块说明
ServerTpl: 服务模块模板

约定: 所有Server的配置属性前缀以Server的name开头(具体以Server中的start方法为准)
  
   ### Netty4HttpServer: http服务
        1. netty4 实现的
        2. threads-boos 属性调节http处理的线程个数
        例: new Netty4HttpServer().setPort(8080).start();
        多个配置属性前缀: http, http-netty
            http.port: 8080
            http-netty.port: 8081
            最终是服务的端口是8081
        
   ### Netty4ResteasyServer: mvc
        1. 基于resteasy实现的mvc功能
        2. 只接收netty4提供的http请求
        例:    AppContext app = new AppContext();
               app.addSource(new Netty4HttpServer().setPort(8080));
               app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
               app.start();
         多个配置属性前缀: mvc, resteasy-netty
               
   ### UndertowResteasySever: mvc
        1. 基于resteasy实现的mvc功能.
        2. 只接收Undertow提供的http请求
        例:    AppContext app = new AppContext();
               app.addSource(new Netty4HttpServer().setPort(8080));
               app.addSource(new UndertowResteasySever().scan(RestTpl.class));
               app.start();
        多个配置属性前缀: mvc, resteasy-undertow
        
   ### MViewServer: 一个系统管理界面(待完善)
   
   ### SwaggerServer: swagger api 文档服务
        1. swagger.openApi: 事件是为收集rest swagger文档.如果需要把某个rest swagger文档显示就得监听此事件(例: 方法SwaggerServer.openApi)
        例:    AppContext app = new AppContext();
               app.addSource(new Netty4HttpServer().setPort(8080));
               app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
               app.addSource(new SwaggerServer());
               app.start();
               然后访问: http://localhost:8080/api-doc/
               
   ### HibernateServer: dao 层 hibernate 实现
        1. 暴露对象: EntityManagerFactory/SessionFactory, TransWrapper(事务包装器)
            例: EntityManagerFactory emf = (EntityManagerFactory) ep.fire("bean.get", EntityManagerFactory.class);
            TransWrapper emf = (TransWrapper) ep.fire("bean.get", TransWrapper.class);
        2. 添加实体对象扫描: app.addSource(new HibernateServer().scanEntity(TestEntity.class) 会扫描TestEntity这个类所在的包下边的所有实体
        3. 添加实体对象扫描: app.addSource(new HibernateServer().scanRepo(TestRepo.class)) 会扫描TestRepo这个类所在的包下边的所有dao对象
        4. 建议所有dao对象 都继承自BaseRepo
        5. 可单独取出 SessionFactory 进行数据库操作
            
   ### EhcacheServer: ehcache 缓存服务
        1. cache.add: 添加缓存
            例: ep.fire("cache.add", "缓存名", "key1", "qqqqqqqqqq");
        2. cache.get: 获取缓存
            例: ep.fire("cache.get", "缓存名", "key1")

   ### SchedServer: quartz 时间任务调度器
        1. sched.cron: cron表达式 来调度任务执行
            例: ep.fire("sched.cron", "31 */2 * * * ? 2019", (Runnable) () -> {// TODO});
        2. sched.after: 多少时间之后执行
            例: ep.fire("sched.after", 45, TimeUnit.SECONDS, (Runnable) () -> {// TODO});
        3. sched.time: 在将来的某个时间点执行
            例: ep.time("sched.time", new Date(new Date().getTime() + 5000), (Runnable) () -> {// TODO});
            
   ### MemSessionManager: session管理(内存session)
        1. 配置: session.expire: 30. 单位分钟
        2. 保存属性到session. 
            ep.fire("session.set", "sessionId", "key1", "value1")
        2. 从session中取属性. 
            ep.fire("session.get", "sessionId", "key1")

## 参与贡献

xnatural@msn.cn


## 码云特技

1. 使用 Readme\_XXX.md 来支持不同的语言，例如 Readme\_en.md, Readme\_zh.md
2. 码云官方博客 [blog.gitee.com](https://blog.gitee.com)
3. 你可以 [https://gitee.com/explore](https://gitee.com/explore) 这个地址来了解码云上的优秀开源项目
4. [GVP](https://gitee.com/gvp) 全称是码云最有价值开源项目，是码云综合评定出的优秀开源项目
5. 码云官方提供的使用手册 [https://gitee.com/help](https://gitee.com/help)
6. 码云封面人物是一档用来展示码云会员风采的栏目 [https://gitee.com/gitee-stars/](https://gitee.com/gitee-stars/)