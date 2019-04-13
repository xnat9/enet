package cn.xnatural.enet.test.dao.repo;

import cn.xnatural.enet.server.dao.hibernate.BaseRepo;
import cn.xnatural.enet.server.dao.hibernate.Repo;
import cn.xnatural.enet.test.dao.entity.TestEntity;

@Repo
public class TestRepo extends BaseRepo<TestEntity, Long> {
}
