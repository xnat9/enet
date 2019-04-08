package org.xnatural.enet.test.dao.repo;

import org.xnatural.enet.server.dao.hibernate.BaseRepo;
import org.xnatural.enet.server.dao.hibernate.Repo;
import org.xnatural.enet.test.dao.entity.TestEntity;

@Repo
public class TestRepo extends BaseRepo<TestEntity, Long> {
}
