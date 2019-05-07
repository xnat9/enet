package cn.xnatural.enet.demo.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import cn.xnatural.enet.server.dao.hibernate.Page;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author xiangxb, 2018-12-08
 */
public class PageModel<T> extends BasePojo {
    @Schema(description = "第几页.从0开始", example = "o")
    private Integer       pageIndex;
    @Schema(description = "每页大小", example = "15")
    private Integer       pageSize;
    @Schema(description = "总页数")
    private Integer       totalPage;
    @Schema(description = "数据集")
    private Collection<T> list;


    /**
     * 空页
     * @return
     */
    public static PageModel empty() {
        return new PageModel().setPageIndex(0).setPageSize(0).setTotalPage(0).setList(Collections.emptyList());
    }


    /**
     * 把dao层的Page转换成PageModel
     * @param page
     * @param <E>
     * @param fn 把Page中的实体转换成T类型的函数
     * @return
     */
    public static <T, E> PageModel<T> of(Page<E> page, Function<E, T> fn) {
        return new PageModel().setTotalPage(page.getTotalPage()).setPageSize(page.getPageSize()).setPageIndex(page.getPageIndex())
                .setList(page.getList().stream().map(e -> fn.apply(e)).collect(Collectors.toList()));
    }


    public Integer getPageIndex() {
        return pageIndex;
    }


    public PageModel<T> setPageIndex(Integer pageIndex) {
        this.pageIndex = pageIndex;
        return this;
    }


    public Integer getPageSize() {
        return pageSize;
    }


    public PageModel<T> setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
        return this;
    }


    public Integer getTotalPage() {
        return totalPage;
    }


    public PageModel<T> setTotalPage(Integer totalPage) {
        this.totalPage = totalPage;
        return this;
    }


    public Collection<T> getList() {
        return list;
    }


    public PageModel<T> setList(Collection<T> list) {
        this.list = list;
        return this;
    }
}
