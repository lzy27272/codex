from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from lxml import html
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    RESULTS.append((name, bool(condition), detail))


required_files = [
    "database/migrations/V1__sprint1_foundation.sql",
    "database/migrations/V2__tenant_row_level_security.sql",
    "database/migrations/V3__sprint1_demo_seed.sql",
    "database/migrations/V38__protect_system_role_governance.sql",
    "docs/openapi.yaml",
    "docs/TEST-REPORT.md",
    "apps/core-api/pom.xml",
    "apps/web/src/App.tsx",
    "infra/production/health/health-check.sh",
    "infra/production/health/health-check.test.sh",
    "apps/web/prototype/index.html",
    "docs/sprint1-ceo-dashboard.png",
    "docs/sprint1-standard-center.png",
    "docs/sprint1-gm-dashboard.png",
]
missing = [path for path in required_files if not (ROOT / path).exists()]
check("交付物文件完整", not missing, ", ".join(missing))

schema = (ROOT / "database/migrations/V1__sprint1_foundation.sql").read_text(encoding="utf-8")
rls = (ROOT / "database/migrations/V2__tenant_row_level_security.sql").read_text(encoding="utf-8")
seed = (ROOT / "database/migrations/V3__sprint1_demo_seed.sql").read_text(encoding="utf-8")
openapi = (ROOT / "docs/openapi.yaml").read_text(encoding="utf-8")
iam_service = (ROOT / "apps/core-api/src/main/java/cn/sifangguan/hotelaios/iam/IamService.java").read_text(encoding="utf-8")
effective_identity_service = (ROOT / "apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/security/EffectiveIdentityService.java").read_text(encoding="utf-8")
v38_migration = (ROOT / "database/migrations/V38__protect_system_role_governance.sql").read_text(encoding="utf-8")
health_check = (ROOT / "infra/production/health/health-check.sh").read_text(encoding="utf-8")
health_check_test = (ROOT / "infra/production/health/health-check.test.sh").read_text(encoding="utf-8")
runtime_install = (ROOT / "infra/production/bootstrap/20-install-runtime.sh").read_text(encoding="utf-8")

required_tables = {
    "tenant", "brand", "org_unit", "org_unit_closure", "hotel_profile",
    "user_account", "employee", "position_definition", "employee_position_assignment",
    "permission", "app_role", "role_permission", "role_assignment",
    "standard_category", "standard_definition", "standard_version", "standard_scope",
    "form_definition", "form_version", "work_record", "attachment",
    "metric_definition", "metric_observation", "audit_log", "outbox_event",
}
created_tables = set(re.findall(r"CREATE TABLE\s+([a-z_]+)\s*\(", schema, flags=re.I))
check("Sprint 1 数据表覆盖", required_tables <= created_tables,
      "缺少: " + ", ".join(sorted(required_tables - created_tables)))

tenant_tables = required_tables - {"tenant", "permission"}
table_blocks = {
    match.group(1): match.group(2)
    for match in re.finditer(r"CREATE TABLE\s+([a-z_]+)\s*\((.*?)\n\);", schema, flags=re.I | re.S)
}
without_tenant = sorted(table for table in tenant_tables if "tenant_id" not in table_blocks.get(table, ""))
check("领域表强制 tenant_id", not without_tenant, ", ".join(without_tenant))

rls_tables = set(re.findall(r"'([a-z_]+)'", rls))
check("RLS 覆盖关键租户表", tenant_tables <= rls_tables,
      "缺少: " + ", ".join(sorted(tenant_tables - rls_tables)))
check("RLS 使用默认拒绝上下文", "current_setting(''app.tenant_id'', true)" in rls and "FORCE ROW LEVEL SECURITY" in rls)

pilot_roles = ["FRONT_DESK", "HOUSEKEEPING_SUPERVISOR", "FRONT_OFFICE_SUPERVISOR", "GENERAL_MANAGER"]
check("四个试点角色已初始化", all(role in seed for role in pilot_roles))
check("一人多岗演示数据已初始化", seed.count("19100000-0000-0000-0000-000000000004") >= 2)
check("六项经营指标已初始化", all(code in seed for code in ["REVENUE", "OCCUPANCY", "ADR", "REVPAR", "OTA_SCORE", "OPERATING_COST"]))

java_files = list((ROOT / "apps/core-api/src/main/java").rglob("*.java"))
java_text = "\n".join(path.read_text(encoding="utf-8") for path in java_files)
check("核心 API Java 模块存在", len(java_files) >= 15, f"Java files={len(java_files)}")
package_mismatches = []
java_root = ROOT / "apps/core-api/src/main/java"
for path in java_files:
    text = path.read_text(encoding="utf-8")
    package_match = re.search(r"^package\s+([\w.]+);", text, flags=re.M)
    if not package_match or Path(*package_match.group(1).split(".")) != path.parent.relative_to(java_root):
        package_mismatches.append(str(path.relative_to(ROOT)))
check("Java包名与目录一致", not package_mismatches, ", ".join(package_mismatches))
tenant_identity_model_files = {"PilotAuthModels.java", "IamModels.java"}
check("业务请求租户不能由 DTO 覆盖", not any(
    "record" in path.read_text(encoding="utf-8")
    and "tenantId" in path.read_text(encoding="utf-8")
    for path in java_files
    if path.name.endswith("Models.java") and path.name not in tenant_identity_model_files
))
check("标准发布仅允许草稿", "lifecycle_status = 'DRAFT'" in (ROOT / "apps/core-api/src/main/java/cn/sifangguan/hotelaios/standards/StandardService.java").read_text(encoding="utf-8"))
check("工作记录校验有效任职", "valid_from <= :businessDate" in (ROOT / "apps/core-api/src/main/java/cn/sifangguan/hotelaios/workdata/WorkDataService.java").read_text(encoding="utf-8"))
check("审计与 Outbox 已接入", "auditWriter.record" in java_text and "auditWriter.emit" in java_text)

controller_paths = {
    "/api/v1/org/units", "/api/v1/org/positions", "/api/v1/org/employees",
    "/api/v1/iam/roles", "/api/v1/standards", "/api/v1/work-data/forms",
    "/api/v1/work-data/records", "/api/v1/metrics/definitions",
    "/api/v1/metrics/observations", "/api/v1/dashboards/ceo",
    "/api/v1/dashboards/hotels", "/api/v1/dashboards/hotels/{hotelId}",
    "/api/v1/dashboards/operations",
}
check("OpenAPI 覆盖核心端点", all(f"  {path}:" in openapi for path in controller_paths))
check("OpenAPI 3.1 声明", openapi.startswith("openapi: 3.1.0"))


def openapi_path_block(path: str) -> str:
    match = re.search(
        rf"^  {re.escape(path)}:\n(.*?)(?=^  /api/v1/|^components:|\Z)",
        openapi,
        flags=re.M | re.S,
    )
    return match.group(1) if match else ""


hotel_discovery_contract = openapi_path_block("/api/v1/dashboards/hotels")
operations_dashboard_contract = openapi_path_block("/api/v1/dashboards/operations")
check(
    "OpenAPI 门店发现权限与响应精确",
    bool(re.search(r"Requires\s+dashboard[.]hotel", hotel_discovery_contract))
    and "#/components/schemas/AccessibleHotels" in hotel_discovery_contract,
)
check(
    "OpenAPI 区域驾驶舱使用独立权限",
    bool(re.search(r"Requires\s+dashboard[.]operations", operations_dashboard_contract))
    and not re.search(r"Requires\s+dashboard[.]hotel", operations_dashboard_contract),
)
check(
    "OpenAPI 通用IAM仅接受CUSTOM角色",
    bool(re.search(
        r"CreateRole:\s+type: object.*?roleType:\s+type: \[string, 'null'\]"
        r"\s+enum: \[CUSTOM, null\]",
        openapi,
        flags=re.S,
    )),
)
check(
    "生产健康检查从当前JAR推导Flyway版本与校验和",
    "read_packaged_flyway_state" in health_check
    and "BOOT-INF/classes/db/migration/V([1-9][0-9]*)__" in health_check
    and "zlib.crc32" in health_check
    and bool(re.search(r"^\s*python3\s*\\$", runtime_install, flags=re.M))
    and "'22|true'" not in health_check,
)
check(
    "生产健康检查拒绝失败历史、缺失迁移与校验和漂移",
    "count(*) filter (where success = false)" in health_check
    and "validate_flyway_database_state" in health_check
    and 'test "${database_migrations}" != "${expected_state}"' in health_check
    and "checksum::text" in health_check,
)
check(
    "生产健康检查具备完整历史门禁负向测试",
    all(marker in health_check_test for marker in [
        "missing intermediate migration is rejected",
        "database ahead is rejected",
        "checksum drift is rejected",
        "failed history row is rejected",
        "duplicate packaged version is rejected",
        "invalid packaged version name is rejected",
    ]),
)
java_reserved_block = re.search(
    r"RESERVED_SYSTEM_ROLE_CODES\s*=\s*Set[.]of\((.*?)\);",
    iam_service,
    flags=re.S,
)
identity_reserved_block = re.search(
    r"RESERVED_SYSTEM_ROLE_CODES\s*=\s*Set[.]of\((.*?)\);",
    effective_identity_service,
    flags=re.S,
)
sql_reserved_block = re.search(
    r"role_type\s*=\s*'CUSTOM'.*?upper\(btrim\(code\)\)\s+IN\s*\((.*?)\)",
    v38_migration,
    flags=re.S | re.I,
)
java_reserved_codes = set(re.findall(r'"([A-Z][A-Z0-9_]*)"', java_reserved_block.group(1))) \
    if java_reserved_block else set()
identity_reserved_codes = set(re.findall(r'"([A-Z][A-Z0-9_]*)"', identity_reserved_block.group(1))) \
    if identity_reserved_block else set()
sql_reserved_codes = set(re.findall(r"'([A-Z][A-Z0-9_]*)'", sql_reserved_block.group(1))) \
    if sql_reserved_block else set()
check(
    "IAM、有效身份与V38系统角色保留代码一致",
    bool(java_reserved_codes)
    and java_reserved_codes == identity_reserved_codes == sql_reserved_codes,
    "iam_vs_identity=" + ",".join(sorted(java_reserved_codes ^ identity_reserved_codes))
    + "; iam_vs_sql=" + ",".join(sorted(java_reserved_codes ^ sql_reserved_codes)),
)
try:
    ET.parse(ROOT / "apps/core-api/pom.xml")
    pom_valid = True
except ET.ParseError:
    pom_valid = False
check("Maven POM XML有效", pom_valid)

test_text = "\n".join(path.read_text(encoding="utf-8") for path in (ROOT / "apps/core-api/src/test/java").rglob("*.java"))
check("JUnit契约测试已提供", test_text.count("@Test") >= 7, f"tests={test_text.count('@Test')}")

prototype_path = ROOT / "apps/web/prototype/index.html"
document = html.fromstring(prototype_path.read_text(encoding="utf-8"))
check("原型包含五个中心入口", len(document.xpath("//nav//button")) == 5)
check("原型支持 CEO/店总切换", len(document.xpath("//*[@data-role]")) == 2)
check("标准中心为结构化列表", len(document.xpath("//*[contains(concat(' ', normalize-space(@class), ' '), ' standard-row ')]")) >= 5)
check("原型具备响应式视口", bool(document.xpath("//meta[@name='viewport']")))

for image_name in ["sprint1-ceo-dashboard.png", "sprint1-standard-center.png", "sprint1-gm-dashboard.png"]:
    image_path = ROOT / "docs" / image_name
    if image_path.exists():
        with Image.open(image_path) as screenshot:
            check(f"截图尺寸 {image_name}", screenshot.size == (1440, 1050), str(screenshot.size))

passed = sum(1 for _, ok, _ in RESULTS if ok)
for index, (name, ok, detail) in enumerate(RESULTS, 1):
    suffix = f" -- {detail}" if detail else ""
    print(f"{'ok' if ok else 'not ok'} {index} - {name}{suffix}")
print(f"\n{passed}/{len(RESULTS)} checks passed")
sys.exit(0 if passed == len(RESULTS) else 1)
