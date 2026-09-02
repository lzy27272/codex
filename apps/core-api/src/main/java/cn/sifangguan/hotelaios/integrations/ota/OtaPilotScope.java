package cn.sifangguan.hotelaios.integrations.ota;

import java.nio.file.Path;
import java.util.UUID;

final class OtaPilotScope {
    static final UUID HOTEL_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    static final String HOTEL_CODE = "002";
    static final String HOTEL_NAME = "解放路MOOODSHIFT酒店";
    static final String PLATFORM_CODE = "CTRIP";
    static final Path SOCKET_PATH = Path.of("/run/sifangguan-ota-ctrip-pilot/pilot.sock");
    static final String AUTHORIZATION_URL_PREFIX = "https://www.sfgzt.cn/ota-pilot/authorize#";

    private OtaPilotScope() {
    }
}
