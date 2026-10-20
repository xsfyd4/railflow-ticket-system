package com.ticket.module.station.mapper;

import com.ticket.module.station.dto.StationQueryDTO;
import com.ticket.module.station.entity.Station;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * station 表的数据访问层。
 *
 * <p>九个方法，对应六种能力：
 * <ul>
 *   <li>列表：findPage + countByQuery（分页必须查两次）</li>
 *   <li>详情：findById</li>
 *   <li>新增：findByCode（查重）+ insert</li>
 *   <li>修改：update</li>
 *   <li>删除：countReferences（查引用）+ deleteById</li>
 *   <li>公开搜索：searchOptions</li>
 * </ul>
 *
 * <p>注意 findPage 和 countByQuery 都收了 query，但 findPage 多收一个 offset。
 * offset 不放 DTO 里，因为它是**算出来的**（(page-1)*size），不是前端传的。
 *
 * <p>XML 里访问 query 的字段要写 {@code #{query.keyword}}，
 * 因为参数用了 @Param("query") 起了名，整个对象成了"参数对象里的一个属性"。
 */
@Mapper
public interface StationMapper {

    /** 分页查列表。offset = (page - 1) * size */
    List<Station> findPage(@Param("query") StationQueryDTO query,
                           @Param("offset") int offset);

    /** 查符合条件的总条数，给分页算 pages 用。WHERE 条件必须和 findPage 一致 */
    long countByQuery(@Param("query") StationQueryDTO query);

    /** 按主键查，查不到返回 null */
    Station findById(@Param("id") Long id);

    /** 按三字码查，用于新增时的唯一性校验 */
    Station findByCode(@Param("stationCode") String stationCode);

    /** 新增，返回影响行数；主键由 useGeneratedKeys 写回 station.id */
    int insert(Station station);

    /** 修改，返回影响行数 */
    int update(Station station);

    /** 按主键删除，返回影响行数 */
    int deleteById(@Param("id") Long id);

    /**
     * 统计这个车站被引用了多少次。
     *
     * <p>要查四张表：train.start_station_id、train.end_station_id、
     * train_stop.station_id、train_segment.from_station_id/to_station_id。
     * 返回总数 > 0 就说明不能删。
     */
    long countReferences(@Param("stationId") Long stationId);

    /**
     * 公开接口用的搜索：按关键词模糊匹配，只返回启用状态的车站，最多 20 条。
     *
     * <p>keyword 为空时返回前 20 个车站（给用户端下拉框做初始列表）。
     */
    List<Station> searchOptions(@Param("keyword") String keyword);
}
