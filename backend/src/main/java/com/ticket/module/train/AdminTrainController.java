package com.ticket.module.train;

import com.ticket.common.result.PageResult;
import com.ticket.common.result.R;
import com.ticket.module.train.dto.TrainQueryDTO;
import com.ticket.module.train.dto.TrainSaveDTO;
import com.ticket.module.train.vo.TrainVO;
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
 * 管理端车次接口。
 *
 * <p>五个方法都只有一行转发，逻辑在 Service 和 XML 里。
 */
@RestController
@RequestMapping("/api/admin/trains")
public class AdminTrainController {

    private final TrainService trainService;

    public AdminTrainController(TrainService trainService) {
        this.trainService = trainService;
    }

    /** GET /api/admin/trains?trainNo=G10&trainType=G&status=1&page=1&size=10 */
    @GetMapping
    public R<PageResult<TrainVO>> page(TrainQueryDTO query) {
        return R.ok(trainService.page(query));
    }

    /** GET /api/admin/trains/{id} */
    @GetMapping("/{id}")
    public R<TrainVO> detail(@PathVariable Long id) {
        return R.ok(trainService.detail(id));
    }

    /** POST /api/admin/trains */
    @PostMapping
    public R<Long> create(@RequestBody @Valid TrainSaveDTO dto) {
        return R.ok("新增成功", trainService.create(dto));
    }

    /** PUT /api/admin/trains/{id} */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody @Valid TrainSaveDTO dto) {
        trainService.update(id, dto);
        return R.ok("修改成功", null);
    }

    /** DELETE /api/admin/trains/{id} */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        trainService.delete(id);
        return R.ok("删除成功", null);
    }
}
