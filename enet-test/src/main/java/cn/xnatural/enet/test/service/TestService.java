package cn.xnatural.enet.test.service;

import cn.xnatural.enet.server.ServerTpl;
import cn.xnatural.enet.server.dao.hibernate.Trans;
import cn.xnatural.enet.test.common.Async;
import cn.xnatural.enet.test.common.Monitor;
import cn.xnatural.enet.test.dao.entity.TestEntity;
import cn.xnatural.enet.test.dao.entity.UploadFile;
import cn.xnatural.enet.test.dao.repo.TestRepo;
import cn.xnatural.enet.test.dao.repo.UploadFileRepo;
import cn.xnatural.enet.test.rest.PageModel;
import cn.xnatural.enet.test.rest.request.AddFileDto;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;

public class TestService extends ServerTpl {
    @Resource
    TestRepo       testRepo;
    @Resource
    UploadFileRepo uploadFileRepo;


    @Monitor
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


    @Async
    @Trans
    public void save(AddFileDto dto) {
        UploadFile e = new UploadFile();
        e.setOriginName(dto.getHeadportrait().getOriginName());
        e.setThirdFileId(dto.getHeadportrait().getResultName());
        uploadFileRepo.saveOrUpdate(e);
    }
}
