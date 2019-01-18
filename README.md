# enet

#### 介绍
事件驱动框架

#### 软件架构
 以AppContext为系统运行上下文, 各小模块服务挂载运行
 * 简单: 各模块不存在编译/启动上的依赖. 最大程度的解耦. 这样各个模块的界限清楚明了
 * 稳定: 各种模块可独立编译打包运行测试. 模块自治
 * 灵活: 可自定义模块, 可随意按需加入到系统中
 * 高效: 事件网络到各个模块异步执行任务充分利用线程资源. 把系统分为两种角色, 执行器(线程池)和执行体.
    ##### 系统运行上下文 AppContext
        1. 环境系统. 为系统本身和各模块提供环境配置
        2. EP 事件中心
            * 事件中心包含了整个系统中的所有监听器, 每个监听器代表一个功能点.
            * 每个模块被加入到系统中时, 系统会自动搜索模块所提供的监听器(即: 有注解@EL的方法)
            * 各模块的功能调用, 只需要触发事件(@EL提供的)就行. coreEp.fire方法
        3. 执行器(线程池). 系统的所有执行最终会扔到这个线程池
        4. 各模块引用


#### 安装教程

```
<dependency>
    <groupId>org.xnatural</groupId>
    <artifactId>enet-all</artifactId>
    <version>0.0.1</version>
</dependency>
```

#### 使用说明
```
app.addSource(new Netty4HttpServer());
app.addSource(new Netty4ResteasyServer());
app.addSource(new MViewServer());
// TODO 加载自定义各个模块
app.start();
```

#### 各模块说明

* Netty4HttpServer: netty4 实现的 http服务
* Netty4ResteasyServer: resteasy 实现的 mvc功能.(接收netty4 提供的http请求)
* MViewServer: 一个界面管理系统

#### 参与贡献

xnatural@msn.cn


#### 码云特技

1. 使用 Readme\_XXX.md 来支持不同的语言，例如 Readme\_en.md, Readme\_zh.md
2. 码云官方博客 [blog.gitee.com](https://blog.gitee.com)
3. 你可以 [https://gitee.com/explore](https://gitee.com/explore) 这个地址来了解码云上的优秀开源项目
4. [GVP](https://gitee.com/gvp) 全称是码云最有价值开源项目，是码云综合评定出的优秀开源项目
5. 码云官方提供的使用手册 [https://gitee.com/help](https://gitee.com/help)
6. 码云封面人物是一档用来展示码云会员风采的栏目 [https://gitee.com/gitee-stars/](https://gitee.com/gitee-stars/)