package com.ticket.module.station;

import com.ticket.common.result.R;
import com.ticket.module.station.vo.StationVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公开的车站接口，不需要登录。
 *
 * <p>给用户端查票页的"出发地/目的地"输入框做联想用：
 * 用户打"北京"，前端调这个接口拿到 [{id:1, name:"北京南"}, ...]，
 * 用户选中后前端记住 id，查票时传 id 而不是中文名。
 *
 * <p>为什么放在 /api/public 而不是 /api/user？
 * 因为查票页在登录前就要能搜索车站——用户还没登录时也得能选出发地。
 */
@RestController
@RequestMapping("/api/public/stations")
public class PublicStationController {

    private final StationService stationService;

    public PublicStationController(StationService stationService) {
        this.stationService = stationService;
    }

    /**
     * GET /api/public/stations?keyword=北京
     *
     * <p>keyword 可以不传（required = false），不传就返回前 20 个启用状态的车站。
     */
    @GetMapping
    public R<List<StationVO>> search(@RequestParam(required = false) String keyword) {
        return R.ok(stationService.searchOptions(keyword));
    }
}
