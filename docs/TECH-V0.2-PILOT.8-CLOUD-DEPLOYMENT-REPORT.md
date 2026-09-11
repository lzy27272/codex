# TECH-V0.2-PILOT.8 云端内部Pilot部署报告

| 项目 | 结果 |
|---|---|
| 产品/设计基线 | PRODUCT-V1.4 / DESIGN-1.1 / TECH-DESIGN-1.0 |
| 部署范围 | 批次A身份与V39底座、批次B董事长受限交办；不含批次C/D |
| 目标 | 腾讯云Ubuntu / `https://www.sfgzt.cn` / GitHub `lzy27272/codex:main` |
| 状态 | DEPLOYED / HEALTHY / FEATURES OFF / FORMAL TECH-V0.2 NO-GO |
| 日期 | 2026-09-12 |

## 1. 收口与制品

- 功能提交：`e001c2ae2c58445bae553667301f0c691c2e7edf`；该提交已快进推送至GitHub `main`并作为首次成功激活的Pilot.8功能基线。
- 后端：`hotel-ai-os-core-api-0.2.0-pilot.8.jar`，首次部署SHA-256为`535937c1bb31c50c26a1b2378e46fd084bb879beb6938b65afa59b373eac02b5`。
- Web：Pilot构建的首次`index.html` SHA-256为`32f1d6574bb8484309f763697d38a9e00949c79d8bab5799131a7083c25cbd76`；公网回读哈希一致。
- 源码变更、JAR、Web压缩包及部署脚本均通过只读敏感信息扫描，0命中、0错误。
- 后端全量测试233项，0失败、0错误、3跳过；Web契约测试58/58 PASS；OpenAPI契约及Pilot生产构建PASS。

## 2. 数据与回滚保障

- 部署前执行服务器既有加密PostgreSQL备份任务，备份文件权限`0600 root:root`，密文SHA-256校验PASS。
- Flyway从V38迁移至V39；服务器健康门禁确认JAR版本39、数据库版本39、失败迁移0。
- 首次切换因systemd尚未加载既有迁移unit，在执行迁移前失败；自动回滚恢复旧Core/Web软链接，API保持UP、数据库保持V38。
- 执行`systemctl daemon-reload`并重新激活已校验候选后迁移成功；该过程证明应用软链接回滚路径有效，没有进行数据库破坏性回退。

## 3. 稳定态验证

| 检查 | 结果 |
|---|---|
| Core API / Caddy | active / active |
| 内网健康 | `UP` |
| 公网首页 | HTTP 200 |
| 服务器与公网首页哈希 | 一致 |
| Flyway JAR / DB | 39 / 39，失败迁移0 |
| 未授权`/api/v1/iam/me` | 401 |
| 未授权`/api/v1/executive-tasks` | 401 |
| 最近5分钟Core API warning | 0 |
| GitHub/云端最终版本 | 最终收口时回读并保持一致 |

Browser插件不可用，且Playwright自带Chromium未安装；遵循前端测试流程，使用机器已有Microsoft Edge与Playwright 1.62.1，不下载新浏览器。桌面1440×900和移动390×844均确认：

- URL和页面标题正确；
- 登录页有完整有意义内容，没有框架错误层；
- 页面加载的同源资源包含`TECH-V0.2-PILOT.8`；
- 登录按钮在空表单→填入→清空密码过程中按禁用→启用→禁用变化，全程没有提交凭证；
- 控制台错误/警告、页面异常及请求失败均为0；
- 截图视觉检查未见白屏、遮挡、溢出或不可读控件。

## 4. 功能边界

- 云端`core-api.env`不存在任何`GROUP_MANAGEMENT_*`变量，应用使用默认false；租户白名单为空。
- 未创建或映射真实董事长、集团总经理、集团副总经理、行政人事主管或行政人事任职。
- 区域经理继续`DEFERRED / NOT IN USE`。
- 批次C周/月计划、批次D提醒Worker和真实人员Pilot数据包尚未实现或启用。
- 本次为内部Pilot增量部署，不等于TECH-V0.2正式发行，不解除既有正式发布门禁。
