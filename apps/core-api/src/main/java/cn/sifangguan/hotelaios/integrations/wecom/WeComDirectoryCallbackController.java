package cn.sifangguan.hotelaios.integrations.wecom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/wecom/directory/callback")
@ConditionalOnProperty(
        name = {"app.wecom.enabled", "app.wecom.directory-sync-enabled"}, havingValue = "true"
)
public class WeComDirectoryCallbackController {
    private final WeComDirectoryCallbackService service;

    public WeComDirectoryCallbackController(WeComDirectoryCallbackService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(
            @RequestParam(name = "msg_signature") String signature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam String echostr
    ) {
        return service.verify(signature, timestamp, nonce, echostr);
    }

    @PostMapping(
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.TEXT_PLAIN_VALUE
    )
    public String receive(
            @RequestParam(name = "msg_signature") String signature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestBody String encryptedEnvelope
    ) {
        return service.receive(signature, timestamp, nonce, encryptedEnvelope);
    }
}

