package com.ticket.module.station;

import com.ticket.common.result.PageResult;
import com.ticket.common.result.R;
import com.ticket.module.station.dto.StationQueryDTO;
import com.ticket.module.station.dto.StationSaveDTO;
import com.ticket.module.station.vo.StationVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端车站接口。
 *
 * <p>五个方法都只有一行转发，没有任何业务逻辑——
 * 真正的逻辑在 StationServiceImpl 里，SQL 在 StationMapper.xml 里。
 */
@RestController
@RequestMapping("/api/admin/stations")
public class AdminStationController {

    private final StationService stationService;

    public AdminStationController(StationService stationService) {
        this.stationService = stationService;
    }

    /**
     * GET /api/admin/stations?keyword=北京&city=北京&status=1&page=1&size=10
     *
     * <p>参数没有写 @RequestParam，而是直接声明成 StationQueryDTO。
     * Spring 会自动把 ?keyword=xx&page=1 这些查询参数按名字绑到 DTO 的字段上。
     */
    @GetMapping
    public R<PageResult<StationVO>> page(StationQueryDTO query) {
        return R.ok(stationService.page(query));
    }

    /** GET /api/admin/stations/{id} —— @PathVariable 从 URL 路径里取值 */
    @GetMapping("/{id}")
    public R<StationVO> detail(@PathVariable Long id) {
        return R.ok(stationService.detail(id));
    }

    /** POST /api/admin/stations —— 返回新车站的 id */
    @PostMapping
    public R<Long> create(@RequestBody @Valid StationSaveDTO dto) {
        return R.ok("新增成功", stationService.create(dto));
    }

    /** PUT /api/admin/stations/{id} */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid StationSaveDTO dto) {
        stationService.update(id, dto);
        return R.ok("修改成功", null);
    }

    /** DELETE /api/admin/stations/{id} */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        stationService.delete(id);
        return R.ok("删除成功", null);
    }
}
