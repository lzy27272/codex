package cn.sifangguan.hotelaios.integrations.ota;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CtripPilotBridgeProperties.class)
public class CtripPilotBridgeConfiguration {
}
