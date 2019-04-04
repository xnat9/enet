package org.xnatural.enet.test.dao.repo;

import org.xnatural.enet.server.dao.hibernate.BaseRepo;
import org.xnatural.enet.server.dao.hibernate.Repository;
import org.xnatural.enet.test.dao.entity.TestEntity;

@Repository
public class TestRepo extends BaseRepo<TestEntity, Long> {
}
