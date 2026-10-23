package com.ticket.module.train;

import com.ticket.common.result.R;
import com.ticket.module.train.vo.TrainStopVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公开的车次接口，不需要登录。
 *
 * <p>给用户端"车次详情"页用：点开一趟车，看它的经停站和到发时刻。
 */
@RestController
@RequestMapping("/api/public/trains")
public class PublicTrainController {

    private final TrainStopService trainStopService;

    public PublicTrainController(TrainStopService trainStopService) {
        this.trainStopService = trainStopService;
    }

    /** GET /api/public/trains/1/stops */
    @GetMapping("/{id}/stops")
    public R<List<TrainStopVO>> stops(@PathVariable Long id) {
        return R.ok(trainStopService.listStops(id));
    }
}
