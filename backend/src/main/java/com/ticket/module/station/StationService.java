package com.ticket.module.station;

import com.ticket.common.result.PageResult;
import com.ticket.module.station.dto.StationQueryDTO;
import com.ticket.module.station.dto.StationSaveDTO;
import com.ticket.module.station.vo.StationVO;

import java.util.List;

/**
 * 车站管理的业务接口。
 */
public interface StationService {

    /** 分页查询。内部要算 offset、兜底 page/size 的非法值 */
    PageResult<StationVO> page(StationQueryDTO query);

    /** 查详情，不存在时抛 BizException */
    StationVO detail(Long id);

    /** 新增，返回新车站的 id；三字码重复时抛 BizException */
    Long create(StationSaveDTO dto);

    /** 修改，车站不存在时抛 BizException */
    void update(Long id, StationSaveDTO dto);

    /** 删除。被车次引用时抛 BizException（提示改用禁用） */
    void delete(Long id);

    /** 公开搜索：给用户端查票页的输入联想用 */
    List<StationVO> searchOptions(String keyword);
}
