package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static cn.sifangguan.hotelaios.integrations.wecom.EmployeeInvitationModels.*;

@RestController
@RequestMapping("/api/v1/integrations/wecom/employee-invitations")
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class EmployeeInvitationController {
    private final EmployeeInvitationService service;

    public EmployeeInvitationController(EmployeeInvitationService service) {
        this.service = service;
    }

    @GetMapping
    public Dashboard dashboard() { return service.dashboard(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestRow create(@Valid @RequestBody CreateRequest request) { return service.create(request); }

    @PostMapping("/{invitationId}/approve")
    public ApprovalResponse approve(
            @PathVariable UUID invitationId, @Valid @RequestBody ApproveRequest request
    ) {
        return service.approve(invitationId, request);
    }

    @PostMapping("/{invitationId}/reject")
    public RequestRow reject(
            @PathVariable UUID invitationId, @Valid @RequestBody RejectRequest request
    ) {
        return service.reject(invitationId, request);
    }
}
