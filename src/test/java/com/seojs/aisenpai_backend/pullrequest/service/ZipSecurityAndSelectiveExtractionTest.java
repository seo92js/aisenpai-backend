package com.seojs.aisenpai_backend.pullrequest.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.InputStream;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ZipSecurityAndSelectiveExtractionTest {

    private final CodeGraphIndexService service = new CodeGraphIndexService(null, null, null, null, null);

    private byte[] createMockZip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(entry.getKey());
                zos.putNextEntry(ze);
                if (entry.getValue() != null) {
                    zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @Test
    void testZipSlipBlocked() throws IOException {
        Map<String, String> entries = new HashMap<>();
        entries.put("repo-root/../../evil.sh", "echo 'evil'");

        byte[] zipBytes = createMockZip(entries);

        Set<String> allFiles = new HashSet<>();
        Map<String, List<String>> fileToRawImports = new HashMap<>();

        assertThrows(SecurityException.class, () -> {
            service.parseZipStream(new ByteArrayInputStream(zipBytes), allFiles, fileToRawImports);
        });
    }

    @Test
    void testSelectiveExtractionAndParsing() throws Exception {
        ReflectionTestUtils.setField(service, "maxArchiveBytes", 50000000L);
        ReflectionTestUtils.setField(service, "maxExtractedFiles", 5000);
        ReflectionTestUtils.setField(service, "maxSourceFileBytes", 1048576L);

        Map<String, String> entries = new HashMap<>();
        entries.put("root/src/Main.java", "package com.example;\nimport com.example.utils.Helper;\npublic class Main {}");
        entries.put("root/src/utils/Helper.java", "package com.example.utils;\npublic class Helper {}");
        entries.put("root/assets/image.png", "binary-data");
        entries.put("root/docs/manual.pdf", "pdf-data");

        byte[] zipBytes = createMockZip(entries);

        Set<String> allFiles = new HashSet<>();
        Map<String, List<String>> fileToRawImports = new HashMap<>();

        service.parseZipStream(new ByteArrayInputStream(zipBytes), allFiles, fileToRawImports);

        assertEquals(2, allFiles.size());
        assertTrue(allFiles.contains("src/Main.java"));
        assertTrue(allFiles.contains("src/utils/Helper.java"));
        assertFalse(allFiles.contains("assets/image.png"));
        assertFalse(allFiles.contains("docs/manual.pdf"));

        assertEquals(1, fileToRawImports.size());
        assertTrue(fileToRawImports.containsKey("src/Main.java"));
        assertEquals(List.of("com.example.utils.Helper"), fileToRawImports.get("src/Main.java"));
    }

    @Test
    void testNextjsDynamicRoutingNotBlocked() throws Exception {
        ReflectionTestUtils.setField(service, "maxArchiveBytes", 50000000L);
        ReflectionTestUtils.setField(service, "maxExtractedFiles", 5000);
        ReflectionTestUtils.setField(service, "maxSourceFileBytes", 1048576L);

        Map<String, String> entries = new HashMap<>();
        entries.put("repo-root/src/app/api/auth/[...nextauth]/route.ts", "import NextAuth from 'next-auth';");

        byte[] zipBytes = createMockZip(entries);

        Set<String> allFiles = new HashSet<>();
        Map<String, List<String>> fileToRawImports = new HashMap<>();

        assertDoesNotThrow(() -> {
            service.parseZipStream(new ByteArrayInputStream(zipBytes), allFiles, fileToRawImports);
        });
        assertTrue(allFiles.contains("src/app/api/auth/[...nextauth]/route.ts"));
    }

    @Test
    void testResolveJsTsImportAbsoluteAndBaseUrl() {
        Set<String> allFiles = Set.of(
                "src/components/Button.tsx",
                "components/Header.js",
                "src/utils/math.ts"
        );

        // Test 1: Absolute import from src/ folder (like baseUrl = "src")
        List<String> resolved1 = CodeGraphIndexService.resolveJsTsImport("src/App.ts", "components/Button", allFiles);
        assertEquals(List.of("src/components/Button.tsx"), resolved1);

        // Test 2: Absolute import from root folder
        List<String> resolved2 = CodeGraphIndexService.resolveJsTsImport("src/App.ts", "components/Header", allFiles);
        assertEquals(List.of("components/Header.js"), resolved2);

        // Test 3: Unresolved external package
        List<String> resolved3 = CodeGraphIndexService.resolveJsTsImport("src/App.ts", "react", allFiles);
        assertTrue(resolved3.isEmpty());
    }

    @Test
    void testStreamClosedOnSuccess() throws Exception {
        InputStream mockStream = spy(new ByteArrayInputStream(createMockZip(Map.of())));
        service.parseZipStream(mockStream, new HashSet<>(), new HashMap<>());
        verify(mockStream).close();
    }

    @Test
    void testStreamClosedOnZipSlipException() throws IOException {
        Map<String, String> entries = Map.of("repo-root/../../evil.sh", "echo 'evil'");
        InputStream mockStream = spy(new ByteArrayInputStream(createMockZip(entries)));

        assertThrows(SecurityException.class, () -> {
            service.parseZipStream(mockStream, new HashSet<>(), new HashMap<>());
        });

        verify(mockStream).close();
    }
}
