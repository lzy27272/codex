package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComUserBindingModels.*;

@RestController
@RequestMapping("/api/v1/integrations/wecom/user-bindings")
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComUserBindingAdministrationController {
    private final WeComUserBindingAdministrationService service;

    public WeComUserBindingAdministrationController(WeComUserBindingAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    public Dashboard dashboard() { return service.dashboard(); }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse invite(@Valid @RequestBody InviteRequest request) { return service.invite(request); }

    @PostMapping("/invitations/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public BulkInviteResponse bulkInvite(@Valid @RequestBody BulkInviteRequest request) {
        return service.bulkInvite(request);
    }

    @PostMapping("/suspend/bulk")
    public BulkOperationResponse bulkSuspend(@Valid @RequestBody BulkSuspendRequest request) {
        return service.bulkSuspend(request);
    }

    @PostMapping("/requests/{requestId}/approve")
    public OperationResult approve(@PathVariable UUID requestId, @Valid @RequestBody DecisionRequest request) {
        return service.approve(requestId, request);
    }

    @PostMapping("/requests/{requestId}/reject")
    public OperationResult reject(@PathVariable UUID requestId, @Valid @RequestBody VersionedReasonRequest request) {
        return service.reject(requestId, request);
    }

    @PostMapping("/requests/{requestId}/cancel")
    public OperationResult cancel(@PathVariable UUID requestId, @Valid @RequestBody VersionedReasonRequest request) {
        return service.cancel(requestId, request);
    }

    @PostMapping("/requests/{requestId}/retry")
    public InviteResponse retry(@PathVariable UUID requestId, @Valid @RequestBody VersionedReasonRequest request) {
        return service.retry(requestId, request);
    }

    @PostMapping("/{accountId}/suspend")
    public OperationResult suspend(@PathVariable UUID accountId, @Valid @RequestBody VersionedReasonRequest request) {
        return service.suspend(accountId, request);
    }

    @PostMapping("/{accountId}/resume")
    public OperationResult resume(@PathVariable UUID accountId, @Valid @RequestBody VersionedReasonRequest request) {
        return service.resume(accountId, request);
    }

    @PostMapping("/{accountId}/revoke")
    public OperationResult revoke(@PathVariable UUID accountId, @Valid @RequestBody VersionedReasonRequest request) {
        return service.revoke(accountId, request);
    }

    @PostMapping("/{accountId}/preferred-assignment")
    public OperationResult preferred(
            @PathVariable UUID accountId, @Valid @RequestBody PreferredAssignmentRequest request
    ) {
        return service.selectPreferred(accountId, request);
    }

    @PostMapping("/{accountId}/rebind")
    public InviteResponse rebind(@PathVariable UUID accountId, @Valid @RequestBody RebindRequest request) {
        return service.rebind(accountId, request);
    }

    @GetMapping("/{accountId}/history")
    public List<AuditEntry> history(@PathVariable UUID accountId) { return service.history(accountId); }
}
