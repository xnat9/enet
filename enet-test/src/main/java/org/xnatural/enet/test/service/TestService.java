package org.xnatural.enet.test.service;

import org.xnatural.enet.server.ServerTpl;
import org.xnatural.enet.server.dao.hibernate.Page;
import org.xnatural.enet.server.dao.hibernate.Trans;
import org.xnatural.enet.test.dao.entity.TestEntity;
import org.xnatural.enet.test.dao.entity.UploadFile;
import org.xnatural.enet.test.dao.repo.TestRepo;
import org.xnatural.enet.test.dao.repo.UploadFileRepo;
import org.xnatural.enet.test.rest.request.AddFileDto;

import java.text.SimpleDateFormat;
import java.util.Date;

public class TestService extends ServerTpl {


    @Trans
    public Page findTestData() {
        TestEntity e = new TestEntity();
        e.setName("aaaa" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        e.setAge(111);
        TestRepo repo = bean(TestRepo.class);
        repo.saveOrUpdate(e);
        return repo.findPage(0, 5, (root, query, cb) -> {query.orderBy(cb.desc(root.get("id"))); return null;});
    }


    @Trans
    public void save(AddFileDto dto) {
        UploadFile e = new UploadFile();
        e.setOriginName(dto.getHeadportrait().getOriginName());
        e.setThirdFileId(dto.getHeadportrait().getResultName());
        bean(UploadFileRepo.class).saveOrUpdate(e);
    }
}
