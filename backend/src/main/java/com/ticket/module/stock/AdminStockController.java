package com.ticket.module.stock;

import com.ticket.common.result.R;
import com.ticket.module.stock.dto.StockAdjustDTO;
import com.ticket.module.stock.dto.StockGenerateDTO;
import com.ticket.module.stock.dto.StockQueryDTO;
import com.ticket.module.stock.vo.StockVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/stocks")
public class AdminStockController {

    private final StockService stockService;

    public AdminStockController(StockService stockService) {
        this.stockService = stockService;
    }

    /** POST /api/admin/stocks/generate —— 为未来 N 天批量生成库存 */
    @PostMapping("/generate")
    public R<Integer> generate(@RequestBody @Valid StockGenerateDTO dto) {
        return R.ok("库存生成完成", stockService.generate(dto));
    }

    /** GET /api/admin/stocks?trainId=1&travelDate=2026-11-01 */
    @GetMapping
    public R<List<StockVO>> list(@Valid StockQueryDTO query) {
        return R.ok(stockService.list(query));
    }

    /** PUT /api/admin/stocks/{id} —— 手工调整余票 */
    @PutMapping("/{id}")
    public R<Void> adjust(@PathVariable Long id, @RequestBody @Valid StockAdjustDTO dto) {
        stockService.adjust(id, dto);
        return R.ok("调整成功", null);
    }
}
