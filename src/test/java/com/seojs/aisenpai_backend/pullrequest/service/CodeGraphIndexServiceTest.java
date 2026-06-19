package com.seojs.aisenpai_backend.pullrequest.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CodeGraphIndexServiceTest {

    @Test
    void parseRawImports_ExtractsPythonImportsCorrectly() {
        // given
        String pyContent = """
            # This is a comment
            import os, sys
            import math as m
            from collections import defaultdict
            from django.db import models as db_models
            from .models import User
            from ..utils import helper
            """;

        // when
        List<String> rawImports = CodeGraphIndexService.parseRawImports(pyContent, "src/main.py");

        // then
        assertEquals(6, rawImports.size());
        assertTrue(rawImports.contains("import os, sys"));
        assertTrue(rawImports.contains("import math as m"));
        assertTrue(rawImports.contains("from collections import defaultdict"));
        assertTrue(rawImports.contains("from django.db import models as db_models"));
        assertTrue(rawImports.contains("from .models import User"));
        assertTrue(rawImports.contains("from ..utils import helper"));
    }

    @Test
    void resolvePythonImport_ResolvesDirectAndFromImports() {
        // given
        Set<String> allFiles = Set.of(
                "src/utils.py",
                "src/models/user.py",
                "src/models/__init__.py",
                "src/config/__init__.py"
        );

        // when & then: 1. Direct import
        List<String> res1 = CodeGraphIndexService.resolvePythonImport("src/main.py", "import src.utils", allFiles);
        assertEquals(1, res1.size());
        assertTrue(res1.contains("src/utils.py"));

        // when & then: 2. From import
        List<String> res2 = CodeGraphIndexService.resolvePythonImport("src/main.py", "from src.models import user", allFiles);
        assertEquals(2, res2.size()); // can resolve either models/user.py or models/__init__.py
        assertTrue(res2.contains("src/models/user.py"));

        // when & then: 3. From import with wildcards
        List<String> res3 = CodeGraphIndexService.resolvePythonImport("src/main.py", "from src.config import *", allFiles);
        assertEquals(1, res3.size());
        assertTrue(res3.contains("src/config/__init__.py"));
    }

    @Test
    void parseRawImports_ExtractsCppIncludesCorrectly() {
        // given
        String cppContent = """
            // This is a C++ comment
            #include "utils/helper.h"
            #include <iostream>
            #include "config.hpp"
            """;

        // when
        List<String> rawImports = CodeGraphIndexService.parseRawImports(cppContent, "src/main.cpp");

        // then
        assertEquals(3, rawImports.size());
        assertTrue(rawImports.contains("utils/helper.h"));
        assertTrue(rawImports.contains("iostream"));
        assertTrue(rawImports.contains("config.hpp"));
    }

    @Test
    void resolveCppImport_ResolvesCppPaths() {
        // given
        Set<String> allFiles = Set.of(
                "src/utils/helper.h",
                "src/config.hpp",
                "src/main.cpp"
        );

        // when & then: 1. Relative include
        List<String> res1 = CodeGraphIndexService.resolveCppImport("src/main.cpp", "utils/helper.h", allFiles);
        assertEquals(1, res1.size());
        assertTrue(res1.contains("src/utils/helper.h"));

        // 2. Suffix match
        List<String> res2 = CodeGraphIndexService.resolveCppImport("src/main.cpp", "config.hpp", allFiles);
        assertEquals(1, res2.size());
        assertTrue(res2.contains("src/config.hpp"));
    }

    @Test
    void parseRawImports_ExtractsKotlinImportsCorrectly() {
        // given
        String ktContent = """
            package com.example
            import com.example.service.MyService
            import com.example.utils.*
            import com.example.models.User as UserModel
            """;

        // when
        List<String> rawImports = CodeGraphIndexService.parseRawImports(ktContent, "src/Main.kt");

        // then
        assertEquals(3, rawImports.size());
        assertTrue(rawImports.contains("com.example.service.MyService"));
        assertTrue(rawImports.contains("com.example.utils.*"));
        assertTrue(rawImports.contains("com.example.models.User"));
    }

    @Test
    void resolveKotlinImport_ResolvesImports() {
        // given
        Set<String> allFiles = Set.of(
                "src/com/example/service/MyService.kt",
                "src/com/example/utils/Helper.kt",
                "src/com/example/utils/Formatter.kts"
        );

        // when & then: 1. Direct import
        List<String> res1 = CodeGraphIndexService.resolveKotlinImport("com.example.service.MyService", allFiles);
        assertEquals(1, res1.size());
        assertTrue(res1.contains("src/com/example/service/MyService.kt"));

        // 2. Wildcard import
        List<String> res2 = CodeGraphIndexService.resolveKotlinImport("com.example.utils.*", allFiles);
        assertEquals(2, res2.size());
        assertTrue(res2.contains("src/com/example/utils/Helper.kt"));
        assertTrue(res2.contains("src/com/example/utils/Formatter.kts"));
    }
}
