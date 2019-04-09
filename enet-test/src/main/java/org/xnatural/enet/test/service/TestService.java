package org.xnatural.enet.test.service;

import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.server.dao.hibernate.Trans;
import org.xnatural.enet.test.common.Async;
import org.xnatural.enet.test.dao.entity.TestEntity;
import org.xnatural.enet.test.dao.entity.UploadFile;
import org.xnatural.enet.test.dao.repo.TestRepo;
import org.xnatural.enet.test.dao.repo.UploadFileRepo;
import org.xnatural.enet.test.rest.PageModel;
import org.xnatural.enet.test.rest.request.AddFileDto;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;

public class TestService extends ServerTpl {
    @Resource
    TestRepo       testRepo;
    @Resource
    UploadFileRepo uploadFileRepo;


    @Trans
    public PageModel findTestData() {
        TestEntity e = new TestEntity();
        e.setName("aaaa" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        e.setAge(111);
        testRepo.saveOrUpdate(e);
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
