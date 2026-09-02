package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeComDirectoryXmlTest {
    private final WeComDirectoryXml xml = new WeComDirectoryXml();

    @Test
    void parsesCreateUserDirectoryEvent() {
        WeComDirectoryEvent event = xml.parseEvent("""
                <xml>
                  <ToUserName><![CDATA[corp-1]]></ToUserName>
                  <CreateTime>1700000000</CreateTime>
                  <Event><![CDATA[change_contact]]></Event>
                  <ChangeType><![CDATA[create_user]]></ChangeType>
                  <UserID><![CDATA[employee-001]]></UserID>
                  <Name><![CDATA[张三]]></Name>
                  <Department><![CDATA[2,3]]></Department>
                  <Position><![CDATA[前台员工]]></Position>
                  <Status>1</Status>
                </xml>
                """);

        assertThat(event.eventType()).isEqualTo("change_contact");
        assertThat(event.changeType()).isEqualTo("create_user");
        assertThat(event.userId()).isEqualTo("employee-001");
        assertThat(event.effectiveUserId()).isEqualTo("employee-001");
        assertThat(event.displayName()).isEqualTo("张三");
        assertThat(event.departmentSnapshot()).isEqualTo("2,3");
        assertThat(event.positionSnapshot()).isEqualTo("前台员工");
        assertThat(event.occurredAt()).isEqualTo(
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(1700000000L), ZoneOffset.UTC));
        assertThat(event.isCreate()).isTrue();
    }

    @Test
    void rejectsXxeAndOversizedXml() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE xml [<!ENTITY leaked SYSTEM "file:///etc/passwd">]>
                <xml>
                  <CreateTime>1700000000</CreateTime>
                  <Event>change_contact</Event>
                  <ChangeType>create_user</ChangeType>
                  <UserID>&leaked;</UserID>
                </xml>
                """;

        assertThatThrownBy(() -> xml.parseEvent(xxe))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> xml.parseEvent("x".repeat(64 * 1024 + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void directoryEventToStringDoesNotExposeUserIds() {
        WeComDirectoryEvent event = new WeComDirectoryEvent(
                "change_contact", "update_user", "sensitive-old-user-id",
                "sensitive-new-user-id", "张三", "1", "2", "前台员工",
                OffsetDateTime.parse("2026-09-01T12:00:00Z"));

        assertThat(event.toString())
                .doesNotContain("sensitive-old-user-id")
                .doesNotContain("sensitive-new-user-id")
                .contains("change_contact", "update_user");
    }
}
