package com.ticket.module.train;

import com.ticket.common.result.PageResult;
import com.ticket.module.train.dto.TrainQueryDTO;
import com.ticket.module.train.dto.TrainSaveDTO;
import com.ticket.module.train.vo.TrainVO;

/**
 * 车次管理的业务接口。
 */
public interface TrainService {

    /** 分页查询，带始发/终到站名 */
    PageResult<TrainVO> page(TrainQueryDTO query);

    /** 查详情 */
    TrainVO detail(Long id);

    /** 新增，返回新 id。车次号重复抛 TRAIN_NO_EXISTS */
    Long create(TrainSaveDTO dto);

    /** 修改 */
    void update(Long id, TrainSaveDTO dto);

    /** 删除。有订单引用时抛 TRAIN_IN_USE，否则级联删除停站/分段/票价/库存 */
    void delete(Long id);

    /**
     * 没有订单时清空某车次全部库存，供管理员重新配置停站。
     * 有订单时拒绝，避免破坏订单的起止段和退票库存。
     */
    void clearStocksForReconfiguration(Long id);
}
