package com.ticket.module.train;

import com.ticket.common.result.R;
import com.ticket.module.train.dto.PricesSaveDTO;
import com.ticket.module.train.dto.StopsSaveDTO;
import com.ticket.module.train.vo.TrainPriceVO;
import com.ticket.module.train.vo.TrainSegmentVO;
import com.ticket.module.train.vo.TrainStopVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端：车次的停站 / 分段 / 票价。
 *
 * <p>路径都是 /api/admin/trains/{trainId}/...，
 * 和 AdminTrainController 共用 /api/admin/trains 这个前缀（路径不冲突）。
 */
@RestController
@RequestMapping("/api/admin/trains")
public class AdminTrainStopController {

    private final TrainStopService trainStopService;

    public AdminTrainStopController(TrainStopService trainStopService) {
        this.trainStopService = trainStopService;
    }

    /** GET /api/admin/trains/1/stops */
    @GetMapping("/{trainId}/stops")
    public R<List<TrainStopVO>> stops(@PathVariable Long trainId) {
        return R.ok(trainStopService.listStops(trainId));
    }

    /** PUT /api/admin/trains/1/stops —— 整体保存停站并重建分段 */
    @PutMapping("/{trainId}/stops")
    public R<Void> saveStops(@PathVariable Long trainId,
                             @RequestBody @Valid StopsSaveDTO dto) {
        trainStopService.saveStops(trainId, dto);
        return R.ok("停站已保存，分段已重建", null);
    }

    /** GET /api/admin/trains/1/segments */
    @GetMapping("/{trainId}/segments")
    public R<List<TrainSegmentVO>> segments(@PathVariable Long trainId) {
        return R.ok(trainStopService.listSegments(trainId));
    }

    /** GET /api/admin/trains/1/prices */
    @GetMapping("/{trainId}/prices")
    public R<List<TrainPriceVO>> prices(@PathVariable Long trainId) {
        return R.ok(trainStopService.listPrices(trainId));
    }

    /** PUT /api/admin/trains/1/prices —— 整体保存票价 */
    @PutMapping("/{trainId}/prices")
    public R<Void> savePrices(@PathVariable Long trainId,
                              @RequestBody @Valid PricesSaveDTO dto) {
        trainStopService.savePrices(trainId, dto);
        return R.ok("票价已保存", null);
    }
}
