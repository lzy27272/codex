package cn.sifangguan.hotelaios;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    @Test
    void dashboardAndIamContractsMatchRuntimeBoundaries() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<String, Object> document;
        try (Reader reader = Files.newBufferedReader(openApiPath())) {
            document = map(new Yaml(new SafeConstructor(options)).load(reader));
        }

        Map<String, Object> paths = map(document.get("paths"));
        Map<String, Object> hotelDiscovery = map(map(paths.get("/api/v1/dashboards/hotels")).get("get"));
        assertThat(String.valueOf(hotelDiscovery.get("description"))).contains("dashboard.hotel");
        assertThat(responseSchemaRef(hotelDiscovery)).isEqualTo("#/components/schemas/AccessibleHotels");

        Map<String, Object> operations = map(map(paths.get("/api/v1/dashboards/operations")).get("get"));
        assertThat(String.valueOf(operations.get("description")))
                .contains("dashboard.operations")
                .doesNotContain("Requires dashboard.hotel");

        Map<String, Object> schemas = map(map(document.get("components")).get("schemas"));
        Map<String, Object> roleType = map(map(map(schemas.get("CreateRole")).get("properties")).get("roleType"));
        assertThat(list(roleType.get("enum"))).containsExactly("CUSTOM", null);
        assertThat(roleType.get("default")).isEqualTo("CUSTOM");

        Map<String, Object> accessibleHotels = map(schemas.get("AccessibleHotels"));
        assertThat(list(accessibleHotels.get("required"))).containsExactly("hotels");
        Map<String, Object> accessibleHotel = map(schemas.get("AccessibleHotel"));
        assertThat(list(accessibleHotel.get("required"))).containsExactly("id", "code", "name");
    }

    private static String responseSchemaRef(Map<String, Object> operation) {
        return String.valueOf(map(map(map(map(map(operation.get("responses")).get("200"))
                .get("content")).get("application/json")).get("schema")).get("$ref"));
    }

    private static Path openApiPath() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path fromRepository = workingDirectory.resolve("docs/openapi.yaml");
        if (Files.isRegularFile(fromRepository)) {
            return fromRepository;
        }
        return workingDirectory.resolve("../../docs/openapi.yaml").normalize();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
