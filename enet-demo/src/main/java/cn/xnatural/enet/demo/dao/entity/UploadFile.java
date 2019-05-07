package cn.xnatural.enet.demo.dao.entity;

import org.hibernate.annotations.DynamicUpdate;
import cn.xnatural.enet.server.dao.hibernate.LongIdEntity;

import javax.persistence.Entity;
import javax.persistence.Inheritance;
import javax.persistence.InheritanceType;
import java.util.Date;

/**
 * 文件上传基本信息实体
 * @author xiangxb, 2018-10-07
 */
@Inheritance(strategy = InheritanceType.JOINED)
@Entity
@DynamicUpdate
public class UploadFile extends LongIdEntity {
    /**
     * 第3方 文件服务器 返回的 文件标识(用于从第3方去获取的凭证)
     */
    private String thirdFileId;
    /**
     * 原始文件名
     */
    private String originName;
    /**
     * 创建时间
     */
    private Date   createTime;
    /**
     * 备注
     */
    private String comment;


    public String getThirdFileId() {
        return thirdFileId;
    }


    public UploadFile setThirdFileId(String thirdFileId) {
        this.thirdFileId = thirdFileId;
        return this;
    }


    public String getOriginName() {
        return originName;
    }


    public UploadFile setOriginName(String originName) {
        this.originName = originName;
        return this;
    }


    public Date getCreateTime() {
        return createTime;
    }


    public UploadFile setCreateTime(Date createTime) {
        this.createTime = createTime;
        return this;
    }


    public String getComment() {
        return comment;
    }


    public UploadFile setComment(String comment) {
        this.comment = comment;
        return this;
    }
}
