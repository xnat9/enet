package org.xnatural.enet.server.dao.hibernate;

import java.io.Serializable;
import java.util.Collection;

public class Page<E> implements Serializable {
    private Integer       pageIndex;
    private Integer       pageSize;
    private Integer       totalPage;
    private Long          totalRow;
    private Collection<E> list;


    public Page(Collection<E> list, Integer pageIndex, Integer pageSize, Long totalRow) {
        this.pageIndex = pageIndex;
        this.pageSize = pageSize;
        setTotalRow(totalRow);
        this.list = list;
    }


    public Page() { }


    public Integer getPageIndex() {
        return pageIndex;
    }


    public Page<E> setPageIndex(Integer pageIndex) {
        this.pageIndex = pageIndex;
        return this;
    }


    public Integer getPageSize() {
        return pageSize;
    }


    public Page<E> setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
        return this;
    }


    public Integer getTotalPage() {
        return totalPage;
    }


    public Page<E> setTotalPage(Integer totalPage) {
        this.totalPage = totalPage;
        return this;
    }


    public Collection<E> getList() {
        return list;
    }


    public Page<E> setList(Collection<E> list) {
        this.list = list;
        return this;
    }


    public Long getTotalRow() {
        return totalRow;
    }


    public Page<E> setTotalRow(Long totalRow) {
        this.totalRow = totalRow;
        this.totalPage = (int) (Math.ceil(totalRow / Double.valueOf(this.pageSize)));
        return this;
    }
}
