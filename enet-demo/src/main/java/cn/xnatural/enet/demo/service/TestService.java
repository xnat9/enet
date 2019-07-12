package cn.xnatural.enet.demo.service;

import cn.xnatural.enet.demo.common.Monitor;
import cn.xnatural.enet.demo.dao.entity.TestEntity;
import cn.xnatural.enet.demo.dao.entity.UploadFile;
import cn.xnatural.enet.demo.dao.repo.TestRepo;
import cn.xnatural.enet.demo.dao.repo.UploadFileRepo;
import cn.xnatural.enet.demo.rest.PageModel;
import cn.xnatural.enet.demo.rest.request.AddFileDto;
import cn.xnatural.enet.event.EC;
import cn.xnatural.enet.event.EL;
import cn.xnatural.enet.server.ServerTpl;
import cn.xnatural.enet.server.dao.hibernate.Trans;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;

public class TestService extends ServerTpl {
    @Resource
    TestRepo       testRepo;
    @Resource
    UploadFileRepo uploadFileRepo;


    @Monitor(trace = true)
    @Trans
    public PageModel findTestData() {
        TestEntity e = new TestEntity();
        e.setName("aaaa" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        e.setAge(111);
        testRepo.saveOrUpdate(e);
        // if (true) throw new IllegalArgumentException("xxx");
        return PageModel.of(
            testRepo.findPage(0, 5, (root, query, cb) -> {query.orderBy(cb.desc(root.get("id"))); return null;}),
            ee -> ee
        );
    }


    @Trans
    public void save(AddFileDto dto) {
        UploadFile e = new UploadFile();
        e.setOriginName(dto.getHeadportrait().getOriginName());
        e.setThirdFileId(dto.getHeadportrait().getResultName());
        uploadFileRepo.saveOrUpdate(e);
    }


    public void remote(String app, String eName, String ret, Consumer fn) {
        // 远程调用
        ep.fire("remote", EC.of(this).args(app, eName, new Object[]{ret}).completeFn(ec -> {
            if (ec.isSuccess()) fn.accept(ec.result);
            else fn.accept(ec.ex() == null ? new Exception(ec.failDesc()) : ec.ex());
        }));
    }


    @EL(name = "eName1", async = false)
    private String testEvent1(String p) {
        TestEntity e = new TestEntity();
        e.setName("aaaa" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        e.setAge(111);
        TestEntity t = testRepo.saveOrUpdate(e);
        // if (true) throw new IllegalArgumentException("xxx");
        return "new entity : " + t.getId();
    }


    @EL(name = "eName2", async = false)
    private Object testEvent2(String p) {
        return testRepo.findPage(0, 20, (root, query, cb) -> {query.orderBy(cb.desc(root.get("id"))); return null;});
    }


    @EL(name = "eName3", async = false)
    private long testEvent3(String p) {
        return testRepo.count((root, query, cb) -> {query.orderBy(cb.desc(root.get("id"))); return null;});
    }


    @EL(name = "eName4", async = false)
    private Object testEvent4(String p) {
        return ep.fire("cache.get","java","java");
    }


    @EL(name = "eName5", async = false)
    private void testEvent5(String p) {
        ep.fire("cache.set","java","java", p);
    }
}
