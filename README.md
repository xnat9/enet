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
            * 各模块的功能调用, 只需要触发事件(@EL提供的)就行. ep.fire方法
        3. 执行器(线程池). 系统的所有执行最终会扔到这个线程池
        4. 各模块引用


## 安装教程
```
<dependency>
    <groupId>org.xnatural.enet</groupId>
    <artifactId>enet-core</artifactId>
    <version>0.0.7</version>
</dependency>
<dependency>
    <groupId>org.xnatural.enet</groupId>
    <artifactId>enet-server</artifactId>
    <version>0.0.7</version>
</dependency>
```

## 用例
可参照模块: enet-test 中的 Launcher 类
```
public class Launcher extends ServerTpl {

    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new Netty4HttpServer());
        app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
        app.addSource(new SwaggerServer());
        app.addSource(new HibernateServer().scanEntity(TestEntity.class).scanRepo(TestRepo.class));
        app.addSource(new EhcacheServer());
        app.addSource(new SchedServer());
        app.addSource(new Launcher(app));
        // TODO 添加其它服务()
        app.start();
    }


    AppContext ctx;
    public Launcher(AppContext ctx) {
        setName("launcher");
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


## 配置
1. 线程池配置
    
    sys.exec.corePoolSize: 4
    
    sys.exec.maximumPoolSize: 8
    
2. 事件中心
    
    ep.track: sys.starting. 要跟踪执行的事件

3. 日志配置
    
    log.level.org.xnatural.enet.event.EP: trace


## enet-server内置可用多模块说明
1. ServerTpl: 服务模块模板. 推荐所有功能模块及业务逻辑service都继承ServerTpl, 并添加到 AppContext中

2. 约定: 所有Server的配置属性前缀以Server的name开头

3. 获取一个指定的bean对象. 
    
    例1: 从全局获取: Object o = ep.fire("bean.get", Class类型, "bean名字")
    
    例2: 指定Server dao中获取: EntityManagerFactory emf = (EntityManagerFactory) ep.fire("dao.bean.get", EntityManagerFactory.class);


   ### Netty4HttpServer: http服务
        1. netty4 实现的
        2. 调节netty io处理的线程个数. http-netty.threads-boos:1
        3. http.port/http-netty.port 属性设置端口.(注: 以http-netty 为前缀的属性值优先级比以http为前缀的属性高)

        例: new Netty4HttpServer().setPort(8080).start();
        
   ### Netty4ResteasyServer: mvc 层
        1. 基于resteasy实现的mvc功能
        2. 只接收netty4提供的http请求
        3. mvc.sessionCookieName: sId. 设置控制session的cookie名字叫sId
        4. mvc.enableSession: true. 设置是否启用session功能
        例:    AppContext app = new AppContext();
               app.addSource(new Netty4HttpServer().setPort(8080));
               app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
               app.start();
              
        
   ### MViewServer: 一个系统管理界面(待完善)
   
   ### SwaggerServer: swagger api 文档服务
        1. swagger.openApi: 事件是为收集rest swagger文档.如果需要把某个rest swagger文档显示就得监听此事件(例: 方法SwaggerServer.openApi)
        2. 添加文档. ep.fire("swagger.addJaxrsDoc", this, "路径前缀", "tag(用于分组)", "描述");
        例:    AppContext app = new AppContext();
               app.addSource(new Netty4HttpServer().setPort(8080));
               app.addSource(new Netty4ResteasyServer().scan(RestTpl.class));
               app.addSource(new SwaggerServer());
               app.start();
               然后访问: http://localhost:8080/api-doc/
               
   ### HibernateServer: dao 层 hibernate 实现
        1. 暴露对象: EntityManagerFactory/SessionFactory, TransWrapper(事务包装器)
            EntityManagerFactory emf = (EntityManagerFactory) ep.fire("dao.bean.get", EntityManagerFactory.class);
            TransWrapper tm = (TransWrapper) ep.fire("dao.bean.get", TransWrapper.class);
        2. 添加实体对象扫描: app.addSource(new HibernateServer().scanEntity(TestEntity.class) 会扫描TestEntity这个类所在的包下边的所有实体
        3. 添加dao(数据访问)对象扫描: app.addSource(new HibernateServer().scanRepo(TestRepo.class)) 会扫描TestRepo这个类所在的包下边的所有dao对象
        4. 建议所有dao对象 都继承自BaseRepo
        5. 可单独取出 SessionFactory 进行数据库操作
            
   ### EhcacheServer: ehcache 缓存
        1. cache.add: 添加缓存
            例: ep.fire("cache.add", "缓存名", "key1", "qqqqqqqqqq");
        2. cache.get: 获取缓存
            例: ep.fire("cache.get", "缓存名", "key1")
        3. 创建自定义缓存
            例: ep.fire("ehcache.create", "缓存名", "过期时间(Duration对象)", "最多保存多少条(heapOfEntries)", "最多大小(单位:MB)")
        4. 使某个缓存key过期
            例: ep.fire("cache.evict", "缓存名", "key1")
        5. 清理某个缓存
            例: ep.fire("cache.clear", "缓存名")

   ### SchedServer: quartz 时间任务调度器
        1. sched.cron: cron表达式 来调度任务执行
            例: ep.fire("sched.cron", "31 */2 * * * ? 2019", (Runnable) () -> {// TODO});
        2. sched.after: 多少时间之后执行
            例: ep.fire("sched.after", 45, TimeUnit.SECONDS, (Runnable) () -> {// TODO});
        3. sched.time: 在将来的某个时间点执行
            例: ep.time("sched.time", new Date(new Date().getTime() + 5000), (Runnable) () -> {// TODO});
            
   ### MemSessionManager: session管理(内存session)
        1. 配置sesson过期时间: session.expire: 30. 单位分钟
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