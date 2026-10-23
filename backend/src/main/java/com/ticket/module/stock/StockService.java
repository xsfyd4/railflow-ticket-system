package com.ticket.module.stock;

import com.ticket.module.stock.dto.StockAdjustDTO;
import com.ticket.module.stock.dto.StockGenerateDTO;
import com.ticket.module.stock.dto.StockQueryDTO;
import com.ticket.module.stock.vo.StockVO;

import java.util.List;

/** 库存管理的业务接口。 */
public interface StockService {

    /** 为未来 N 天批量生成库存，返回新插入的条数 */
    int generate(StockGenerateDTO dto);

    /** 查某天某车次的逐段余票 */
    List<StockVO> list(StockQueryDTO query);

    /** 手工调整某段某席别的余票 */
    void adjust(Long id, StockAdjustDTO dto);
}
