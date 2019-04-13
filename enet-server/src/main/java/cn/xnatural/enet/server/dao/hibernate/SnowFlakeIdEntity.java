package cn.xnatural.enet.server.dao.hibernate;

import org.hibernate.annotations.GenericGenerator;

import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.MappedSuperclass;

/**
 * SnowFlake id 生成策略
 *
 * @author xiangxb, 2018-10-05
 */
@MappedSuperclass
public class SnowFlakeIdEntity extends BaseEntity {
    @Id
    @GeneratedValue(generator = "snowFlakeId")
    @GenericGenerator(name = "snowFlakeId", strategy = "org.xnatural.shop.common.SnowFlakeIdGenerator")
    private Long id;


    public SnowFlakeIdEntity() {
    }


    public SnowFlakeIdEntity(Long id) {
        this.id = id;
    }


    public Long getId() {
        return id;
    }


    public SnowFlakeIdEntity setId(Long id) {
        this.id = id;
        return this;
    }
}
