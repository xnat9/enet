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
可参照模块: enet-test 中的 [Launcher](https://gitee.com/xnat/enet/blob/master/enet-test/src/main/java/org/xnatural/enet/test/Launcher.java) 类
```
public class Launcher extends ServerTpl {

    public static void main(String[] args) {
        AppContext app = new AppContext();
        app.addSource(new NettyHttp());
        app.addSource(new NettyResteasy().scan(RestTpl.class));
        app.addSource(new SwaggerApiDoc());
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

## Environment 环境配置
    1. 只支持 Properties 文件配置.(因为感觉有这个就够了). 支持 ${attr} 替换
    2. 常用配置
        env.profiles.active: dev // 启动哪一套配置(和spring boot一样)
        核心线程池配置: 
            sys.exec.corePoolSize: 4
            sys.exec.maximumPoolSize: 8
            sys.exec.keepAliveTime: 120 (秒)
        事件中心的配置:
            ep.track: sys.starting // 跟踪事件的执行(多个以逗号分割)
        日志:
            log.level.日志名: trace/debug/info/warn/error
            跟踪所有事件的执行: log.level.org.xnatural.enet.event.EP: trace
    3. 取所有以http为前缀的属性集
            Map<String, String> m = (Map) ep.fire("env.ns", "http");
            此时就可以配置: http.hostname, http.port 等属性了 


## 事件驱动
```
    public class TestEP {
        public static void main(String[] args) {
            //1. 创建一个事件中心(解析对象中的所有事件方法, 和触发发布事件)
            EP ep = new EP();
            //2. 解析对象中的事件方法. 此步骤会解析出对象中所有包含@EL的方法, 并加入到 ep(事件中心)去
            ep.addListenerSource(new TestEP());
            
            // 触发并发布事件. 触发hello事件, "enet"参数会被传到hello事件所指向的方法的参数
            ep.fire("hello", "enet");
            // 事件执行完 回调
            ep.fire("hello", "enet", (ec) -> {// TODO});
            // 开启debug模式
            ep.fire("hello", EC.of(this).debug(), "enet")
            // 同步执行的事件, 可以直接取返回值
            Object r = ep.fire("get");
            
            // 同步执行
                1. ep.fire("hello", EC.of(this).sync(), "enet")
                2. @EL async 设置为false
            // 默认填充参数为null
            ep.fire("hello"); // 此时会打印 hello null
            
            // 每次触发一个事件 都会有一个 EC 对象(事件执行上下文)
            ec.fire("ec"); // 自动创建EC对象
            ec.fire("ec", EC.of(this).attr("key1", "value1")); // 手动创建EC对象
        }
        
        // 此方法被标注为一个名叫hello的事件方法.
        @EL(name = "hello")
        private void hello(String name) {
            System.out.println("hello " + name);
        }
        
        @EL(name = "get", async = false)
        private String get() {
            return "xxx";
        }
        
        @EL(name = "ec")
        private void ec(EC ec) {
            // 事件源
            Object s = ec.source();
            // 取事件下文中的属性
            String value1 = ec.getAttr("key1", String.class);
        }
    }
```


## 事件说明
    sys.starting: 系统启动. 各服务接收到启动事件后各自并行启动
    sys.started: 系统启动完成. 即所有服务都已启动完成
    sys.stopping: 系统关闭通知.
    env.configured: 环境已配置完成. 即各配置属性已可取得
    env.updateAttr: 有属性改变. 可在运行时改变某属性


## enet-server提供的模块说明
1. ServerTpl: 服务模块模板. 推荐所有功能模块及业务逻辑service都继承ServerTpl, 并添加到 AppContext中

2. 约定: 所有Server的配置属性前缀以Server的name开头

3. 获取一个指定的bean对象. 
    
    例1: 从全局获取: Object o = ep.fire("bean.get", Class类型, "bean名字")
    
    例2: 指定Server dao中获取: EntityManagerFactory emf = (EntityManagerFactory) ep.fire("dao.bean.get", EntityManagerFactory.class);


   ### NettyHttp: http服务
        1. netty4 实现的
        2. 调节netty io处理的线程个数. http-netty.threads-boos:1
        3. http.port/http-netty.port 属性设置端口.(注: 以http-netty 为前缀的属性值优先级比以http为前缀的属性高)

        例: new NettyHttp().setPort(8080).start();


   ### NettyResteasy: mvc 层
        1. 基于resteasy实现的mvc功能
        2. 只接收netty4提供的http请求
        3. mvc.sessionCookieName: sId. 设置控制session的cookie名字叫sId
        4. mvc.enableSession: true. 设置是否启用session功能
        例:    AppContext app = new AppContext();
               app.addSource(new NettyHttp().setPort(8080));
               app.addSource(new NettyResteasy().scan(RestTpl.class));
               app.start();
   
   ### SwaggerApiDoc: swagger api 文档服务
        1. swagger.openApi: 事件是为收集rest swagger文档.如果需要把某个rest swagger文档显示就得监听此事件(例: 方法Swagger.openApi)
        2. 添加文档. ep.fire("swagger.addJaxrsDoc", this, "路径前缀", "tag(用于分组)", "描述");
        例:    AppContext app = new AppContext();
               app.addSource(new NettyHttp().setPort(8080));
               app.addSource(new NettyResteasyServer().scan(RestTpl.class));
               app.addSource(new SwaggerApiDoc());
               app.start();
               然后访问: http://localhost:8080/api-doc/

   ### Hibernate: dao 层 hibernate 实现
        1. 暴露对象: EntityManagerFactory/SessionFactory, TransWrapper(事务包装器)
            EntityManagerFactory emf = (EntityManagerFactory) ep.fire("dao.bean.get", EntityManagerFactory.class);
            TransWrapper tm = (TransWrapper) ep.fire("dao.bean.get", TransWrapper.class);
        2. 添加实体对象扫描: app.addSource(new Hibernate().scanEntity(TestEntity.class) 会扫描TestEntity这个类所在的包下边的所有实体
        3. 添加dao(数据访问)对象扫描: app.addSource(new Hibernate().scanRepo(TestRepo.class)) 会扫描TestRepo这个类所在的包下边的所有dao对象
        4. 建议所有dao对象 都继承自BaseRepo
        5. 可单独取出 SessionFactory 进行数据库操作
        6. repo 对象例子: [TestRepo](https://gitee.com/xnat/enet/blob/master/enet-test/src/main/java/org/xnatural/enet/test/TestRepo.java)

     
   ### EhcacheServer: ehcache 缓存
        配置某个缓存的过期时间: cache.expire.缓存名: 3600. (单位秒)  
        1. cache.set: 设置缓存
            例: ep.fire("cache.set", "缓存名", "key1", "qqqqqqqqqq");
        2. cache.get: 获取缓存
            例: ep.fire("cache.get", "缓存名", "key1")
        3. 创建自定义缓存
            例: ep.fire("ehcache.create", "缓存名", "过期时间(Duration对象)", "最多保存多少条(heapOfEntries)", "最多大小(单位:MB)")
        4. 使某个缓存key过期
            例: ep.fire("cache.evict", "缓存名", "key1")
        5. 清理某个缓存
            例: ep.fire("cache.clear", "缓存名")


   ### XMemcached: memcached 缓存
        配置
            a. 配置服务器连接: memecached.hosts: localhost:11211
            b. 某个缓存的过期时间: cache.expire.缓存名: 3600. (单位秒)
            c. 配置连接池大小: memcahced.poolSize: 7
            
        1. cache.set: 设置缓存
            例: ep.fire("cache.set", "缓存名", "key1", "qqqqqqqqqq");
        2. cache.get: 获取缓存
            例: ep.fire("cache.get", "缓存名", "key1")
        3. 使某个缓存key过期
            例: ep.fire("cache.evict", "缓存名", "key1")
        4. 清理某个缓存
            例: ep.fire("cache.clear", "缓存名")
   
   
   ### RedisServer: redis 缓存
        配置
            连接主机: redis.host: localhost
            连接主机端口: redis.port: 6379
            连接密码: redis.password: xxx
            连接库: redis.database: 0
            连接超时: redis.timeout: 2000
            最小空闲连接数: redis.minIdle: 2
            最大空闲连接数: redis.maxIdle: 2
            最大连接数: redis.maxTotal: 2
            最大等待时间: redis.maxWaitMillis: 2
       
       1. cache.set: 设置hash数据
           例: ep.fire("redis.hset", "缓存名", "key1", "qqqqqqqqqq", "过期时间(秒)");
       2. cache.get: 获取hash某个key的数据
           例: ep.fire("redis.hget", "缓存名", "key1")
       3. 删除某个hash key
           例: ep.fire("cache.hdel", "缓存名", "key1")
       4. 清理某个缓存
           例: ep.fire("cache.del", "缓存名")
       5. 获取一个Jedis对象, 执行自定义操作
           例: ep.fire("redis.exec", (Function<Jedis, Object>) (c, o) -> {return null;})


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
        3. 从session中取属性.
            ep.fire("session.get", "sessionId", "key1")

   ### RedisSessionManager: session管理(redis)
        1. 需要配置 session.type: redis 才能生效
        2. 服务依赖 RedisServer
        3. 配置sesson过期时间: session.expire: 30. 单位分钟
        4. 保存属性到session. 
            ep.fire("session.set", "sessionId", "key1", "value1")
        5. 从session中取属性. 
            ep.fire("session.get", "sessionId", "key1")


## mongo 用法例子
[mongo-client](https://gitee.com/xnat/enet/wikis/mongo%E4%BD%BF%E7%94%A8?sort_id=1400974)

## eureka 注册例子
[eurek-client](https://gitee.com/xnat/enet/wikis/eureka%E6%B3%A8%E5%86%8C?sort_id=1400954)

## 参与贡献

xnatural@msn.cn