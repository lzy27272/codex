#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=health-check.sh
source "${script_dir}/health-check.sh"

fail() {
  printf 'not ok - %s\n' "$1" >&2
  exit 1
}

expect_failure() {
  local name="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    fail "${name}"
  fi
  printf 'ok - %s\n' "${name}"
}

valid_listing=$'META-INF/MANIFEST.MF\nBOOT-INF/classes/db/migration/V1__foundation.sql\nBOOT-INF/classes/db/migration/V9__notifications.sql\nBOOT-INF/classes/db/migration/V37__navigation.sql\nBOOT-INF/classes/db/migration/R__refresh_view.sql'
test "$(resolve_packaged_flyway_version "${valid_listing}")" = '37'
printf '%s\n' 'ok - highest packaged numeric migration is selected'

expected_state='1:101,9:-202,37:303'
validate_flyway_database_state "${expected_state}" '1:101,9:-202,37:303|0|0'
printf '%s\n' 'ok - matching database state is accepted'

expect_failure 'missing intermediate migration is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,37:303|0|0'
expect_failure 'database ahead is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,9:-202,37:303,38:404|0|0'
expect_failure 'checksum drift is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,9:-999,37:303|0|0'
expect_failure 'failed history row is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,9:-202,37:303|1|0'
expect_failure 'non-numeric or checksum-less row is rejected' \
  validate_flyway_database_state "${expected_state}" '1:101,9:-202,37:303|0|1'
expect_failure 'empty database state is rejected' \
  validate_flyway_database_state "${expected_state}" ''

duplicate_listing=$'BOOT-INF/classes/db/migration/V37__one.sql\nBOOT-INF/classes/db/migration/V37__two.sql'
expect_failure 'duplicate packaged version is rejected' resolve_packaged_flyway_version "${duplicate_listing}"

invalid_listing=$'BOOT-INF/classes/db/migration/V37_1__invalid.sql\nBOOT-INF/classes/db/migration/V37__valid.sql'
expect_failure 'invalid packaged version name is rejected' resolve_packaged_flyway_version "${invalid_listing}"

expect_failure 'JAR without versioned migrations is rejected' \
  resolve_packaged_flyway_version 'BOOT-INF/classes/db/migration/R__refresh_view.sql'

printf '%s\n' 'HEALTH_CHECK_MIGRATION_GATE_TESTS_OK'
