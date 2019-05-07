package cn.xnatural.enet.demo.dao.repo;

import cn.xnatural.enet.server.dao.hibernate.BaseRepo;
import cn.xnatural.enet.server.dao.hibernate.Repo;
import cn.xnatural.enet.demo.dao.entity.TestEntity;

@Repo
public class TestRepo extends BaseRepo<TestEntity, Long> {
}
