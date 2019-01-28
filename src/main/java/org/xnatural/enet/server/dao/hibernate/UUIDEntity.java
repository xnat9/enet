package org.xnatural.enet.server.dao.hibernate;

import org.hibernate.annotations.GenericGenerator;

import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;

/**
 * UUID 作为实体 主键id
 * @author xiangxb, 2018-10-01
 */
@MappedSuperclass
public class UUIDEntity extends BaseEntity {
    @Id
    @GeneratedValue(generator = "uuid")
    @GenericGenerator(name = "uuid", strategy = "org.hibernate.id.UUIDGenerator")
    // UUIDGenerationStrategy
    private String id;


    public UUIDEntity() {}

    public UUIDEntity(String id) {
        this.id = id;
    }


    public String getId() {
        return id;
    }


    public UUIDEntity setId(String id) {
        this.id = id;
        return this;
    }
}
