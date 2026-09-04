package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.service.ScheduleTaskService;
import com.smartquery.service.ScheduleTaskService.ScheduleTaskCommand;
import com.smartquery.service.ScheduleTaskService.ScheduleTaskView;
import com.smartquery.service.ModelScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/schedule-tasks")
@RequiredArgsConstructor
public class ScheduleTaskController {
    private final ScheduleTaskService scheduleTaskService;
    private final ModelScheduleService modelScheduleService;

    @GetMapping
    public Result<List<ScheduleTaskView>> list() {
        return Result.ok(scheduleTaskService.list());
    }

    @PostMapping
    public Result<ScheduleTaskView> create(@RequestBody ScheduleTaskCommand command) {
        return Result.ok(scheduleTaskService.create(command));
    }

    @PutMapping("/{id}")
    public Result<ScheduleTaskView> update(@PathVariable Long id, @RequestBody ScheduleTaskCommand command) {
        return Result.ok(scheduleTaskService.update(id, command));
    }

    @PutMapping("/{id}/status")
    public Result<ScheduleTaskView> changeStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return Result.ok(scheduleTaskService.changeStatus(id, body.get("status")));
    }

    @PostMapping("/{id}/run-now")
    public Result<ModelScheduleService.ScheduleRunResult> runNow(@PathVariable Long id) {
        return Result.ok(modelScheduleService.runNow(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        scheduleTaskService.delete(id);
        return Result.ok();
    }
}
