package com.ticket.module.station.impl;

import com.ticket.common.exception.BizException;
import com.ticket.common.result.PageResult;
import com.ticket.common.result.ResultCode;
import com.ticket.module.station.StationService;
import com.ticket.module.station.dto.StationQueryDTO;
import com.ticket.module.station.dto.StationSaveDTO;
import com.ticket.module.station.entity.Station;
import com.ticket.module.station.mapper.StationMapper;
import com.ticket.module.station.vo.StationVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 车站管理的实现。
 *
 * <p>这个类里有三处**业务规则**，是面试时能讲的东西：
 * <ol>
 *   <li>page/size 的兜底：size 不能是负数，也不能太大（防恶意请求）</li>
 *   <li>三字码唯一：查一次 + 数据库唯一索引兜底（两个都要）</li>
 *   <li>删除前查引用：被车次用过的车站不能删，只能禁用</li>
 * </ol>
 */
@Service
public class StationServiceImpl implements StationService {

    private final StationMapper stationMapper;

    public StationServiceImpl(StationMapper stationMapper) {
        this.stationMapper = stationMapper;
    }

    @Override
    public PageResult<StationVO> page(StationQueryDTO query) {
        // 1. 兜底：page 最小 1，size 限制在 1~100

        int page=(query.getPage()==null||query.getPage()<1)?1:query.getPage();
        int size=(query.getSize()==null||query.getSize()<1)?10:query.getSize();
        if (size>100){
            size=100;
        }

        // ★ 把修正后的值写回 query。
        // XML 里的 LIMIT 用的是 #{query.size}，不写回去的话，
        // 前端传 size=999999 依然会把整个表拉出来。
        query.setPage(page);
        query.setSize(size);

        // 2. 算 offset
        int offset = (page - 1) * size;

        // 3. 查两次：总数 + 当页数据
        long total = stationMapper.countByQuery(query);
        List<Station> list = stationMapper.findPage(query, offset);

        // 4. Entity → VO，再组装分页对象
        List<StationVO> records = list.stream().map(StationVO::from).toList();
        return PageResult.of(total, page, size, records);
    }

    @Override
    public StationVO detail(Long id) {
        Station station = stationMapper.findById(id);
        if (station == null) {
            throw new BizException(ResultCode.STATION_NOT_FOUND);
        }
        return StationVO.from(station);
    }

    @Override
    public Long create(StationSaveDTO dto) {

        // 1. 查重：同一个三字码只能有一个车站
        Station existing = stationMapper.findByCode(dto.getStationCode());
        if (existing != null) {
            throw new BizException(ResultCode.STATION_CODE_EXISTS);
        }

        // 2. DTO → Entity
        Station station = new Station();
        station.setStationCode(dto.getStationCode());
        station.setStationName(dto.getStationName());
        station.setCity(dto.getCity());
        station.setProvince(dto.getProvince());
        station.setPinyin(dto.getPinyin());
        station.setPinyinAbbr(dto.getPinyinAbbr());

        // status 是可选参数，没传就默认启用
        station.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());

        // 3. 插入
        try {
            stationMapper.insert(station);
        } catch (DuplicateKeyException e) {
            // 防止并发：两个人同时新增同一个三字码
            throw new BizException(ResultCode.STATION_CODE_EXISTS);
        }

        // 4. 返回主键
        return station.getId();
    }

    @Override
    public void update(Long id, StationSaveDTO dto) {
        // TODO
        Station station=stationMapper.findById(id);
        if (station==null){
            throw new BizException(ResultCode.STATION_NOT_FOUND);
        }
        boolean codeUnchanged = dto.getStationCode().equals(station.getStationCode());
        boolean codeAvailable = stationMapper.findByCode(dto.getStationCode()) == null;
        if (!codeUnchanged && !codeAvailable){
            throw new BizException(ResultCode.STATION_CODE_EXISTS);
        }
        station.setStationCode(dto.getStationCode());
        station.setStationName(dto.getStationName());
        station.setCity(dto.getCity());
        station.setProvince(dto.getProvince());
        station.setPinyin(dto.getPinyin());
        station.setPinyinAbbr(dto.getPinyinAbbr());
        // status 没传时保持原值，避免把停用的车站悄悄启用
        station.setStatus(dto.getStatus() == null ? station.getStatus() : dto.getStatus());
        stationMapper.update(station);

    }

    @Override
    public void delete(Long id) {
        // 1. 车站得存在
        Station station = stationMapper.findById(id);
        if (station == null) {
            throw new BizException(ResultCode.STATION_NOT_FOUND);
        }

        // 2. 被车次引用过就不能删（删了会让车次/停站指向一个不存在的车站）
        long refs = stationMapper.countReferences(id);
        if (refs > 0) {
            throw new BizException(ResultCode.STATION_IN_USE);
        }

        // 3. 没人用，真删
        stationMapper.deleteById(id);
    }

    @Override
    public List<StationVO> searchOptions(String keyword) {
        return stationMapper.searchOptions(keyword).stream()
                .map(StationVO::from)
                .toList();
    }
}
