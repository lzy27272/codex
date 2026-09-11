package cn.sifangguan.hotelaios.tasks;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/executive-tasks")
public class ExecutiveTaskController {
    private final ExecutiveTaskService service;

    public ExecutiveTaskController(ExecutiveTaskService service) {
        this.service = service;
    }

    @GetMapping
    public List<ExecutiveTaskModels.ExecutiveTaskSummary> list() {
        return service.list();
    }

    @GetMapping("/{taskId}")
    public ExecutiveTaskModels.ExecutiveTaskDetail detail(@PathVariable UUID taskId) {
        return service.detail(taskId);
    }

    @GetMapping("/targets")
    public List<ExecutiveTaskModels.ExecutiveTaskTarget> targets() {
        return service.targets();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExecutiveTaskModels.ExecutiveTaskDetail create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ExecutiveTaskModels.CreateExecutiveTask request
    ) {
        return service.assignByChairman(request, idempotencyKey);
    }

    @PostMapping("/{taskId}/actions/approve")
    public ExecutiveTaskModels.ExecutiveTaskDetail approve(
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ExecutiveTaskModels.ApproveExecutiveTask request
    ) {
        return service.approve(taskId, request, idempotencyKey);
    }

    @PostMapping("/{taskId}/actions/rework")
    public ExecutiveTaskModels.ExecutiveTaskDetail rework(
            @PathVariable UUID taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ExecutiveTaskModels.ReworkExecutiveTask request
    ) {
        return service.rework(taskId, request, idempotencyKey);
    }
}
