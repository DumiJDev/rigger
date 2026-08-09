package io.rigger.schema;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryTest {

    @Test void allKindsHaveSchemas() {
        for (String kind : SchemaRegistry.registeredKinds()) {
            var url = SchemaRegistry.schemaUrlFor(kind);
            assertTrue(url.isPresent(), "Missing schema for kind: " + kind);
        }
    }

    @Test void unknownKind_returnsEmpty() {
        assertTrue(SchemaRegistry.schemaUrlFor("StatefulSet").isEmpty());
    }

    @Test void schemaFiles_areReadable() {
        SchemaRegistry.registeredKinds().forEach(kind -> {
            var url = SchemaRegistry.schemaUrlFor(kind);
            assertTrue(url.isPresent());
            assertDoesNotThrow(() -> url.get().openStream().close(),
                "Cannot open schema for " + kind);
        });
    }
}
