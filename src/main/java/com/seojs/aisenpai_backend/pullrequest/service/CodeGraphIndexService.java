package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import com.seojs.aisenpai_backend.github.entity.RepositoryAiSettings;
import com.seojs.aisenpai_backend.github.service.RepositoryAiSettingsService;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeFileDependency;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeFileDependency.RelationType;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeFileDependency.ResolutionStatus;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeGraphIndex;
import com.seojs.aisenpai_backend.pullrequest.repository.CodeFileDependencyRepository;
import com.seojs.aisenpai_backend.pullrequest.repository.CodeGraphIndexRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@RequiredArgsConstructor
@Service
public class CodeGraphIndexService {

    private final CodeGraphIndexRepository codeGraphIndexRepository;
    private final CodeFileDependencyRepository codeFileDependencyRepository;
    private final RepositoryAiSettingsService repositoryAiSettingsService;
    private final TokenEncryptionService tokenEncryptionService;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.index.max-archive-bytes:52428800}")
    private long maxArchiveBytes;

    @Value("${app.index.max-extracted-files:5000}")
    private int maxExtractedFiles;

    @Value("${app.index.max-source-file-bytes:1048576}")
    private long maxSourceFileBytes;

    @Value("${app.index.timeout-seconds:300}")
    private int indexTimeoutSeconds;

    private final ExecutorService indexingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "code-graph-indexing-thread");
        thread.setDaemon(true);
        return thread;
    });

    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "code-graph-worker-thread");
        thread.setDaemon(true);
        return thread;
    });

    static final Pattern JAVA_IMPORT_PATTERN = Pattern.compile("import\\s+(?:static\\s+)?([a-zA-Z0-9._*]+);");
    static final Pattern JS_IMPORT_PATTERN_1 = Pattern.compile("import\\s+(?s:.*?)\\s+from\\s+['\"]([^'\"]+)['\"]");
    static final Pattern JS_IMPORT_PATTERN_2 = Pattern.compile("import\\s+['\"]([^'\"]+)['\"]");
    static final Pattern JS_REQUIRE_PATTERN = Pattern.compile("require\\s*\\(\\s*['\"]([^'\"]+)\\s*['\"]\\)");

    @PreDestroy
    public void shutdown() {
        indexingExecutor.shutdownNow();
        workerExecutor.shutdownNow();
    }

    public void submitIndexingTask(Long repositoryId, String refName, String commitSha, boolean defaultBranch) {
        log.info("Submitting indexing task for repositoryId={}, refName={}, commitSha={}, defaultBranch={}", 
                repositoryId, refName, commitSha, defaultBranch);
        
        indexingExecutor.submit(() -> {
            log.info("Starting indexing task for repositoryId={}, commitSha={}", repositoryId, commitSha);
            Future<?> future = workerExecutor.submit(() -> {
                try {
                    runIndexing(repositoryId, refName, commitSha, defaultBranch);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                future.get(indexTimeoutSeconds, TimeUnit.SECONDS);
                log.info("Completed indexing task for repositoryId={}, commitSha={}", repositoryId, commitSha);
            } catch (TimeoutException e) {
                log.error("Indexing timed out for repositoryId={}, commitSha={}", repositoryId, commitSha);
                future.cancel(true);
                markIndexFailed(repositoryId, commitSha, "Timeout of " + indexTimeoutSeconds + " seconds exceeded.");
            } catch (InterruptedException e) {
                log.warn("Indexing interrupted for repositoryId={}, commitSha={}", repositoryId, commitSha);
                future.cancel(true);
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.error("Indexing failed for repositoryId={}, commitSha={}", repositoryId, commitSha, e.getCause());
                markIndexFailed(repositoryId, commitSha, e.getCause().getMessage());
            } catch (Exception e) {
                log.error("Indexing failed for repositoryId={}, commitSha={}", repositoryId, commitSha, e);
                markIndexFailed(repositoryId, commitSha, e.getMessage());
            }
        });
    }

    public void runIndexing(Long repositoryId, String refName, String commitSha, boolean defaultBranch) throws Exception {
        CodeGraphIndex index = new TransactionTemplate(transactionManager).execute(status -> 
            createOrGetIndexingState(repositoryId, refName, commitSha, defaultBranch)
        );

        if (index == null) {
            throw new IllegalStateException("Failed to initialize or retrieve CodeGraphIndex");
        }

        if (index.getStatus() == CodeGraphIndex.Status.READY) {
            log.info("Index for repositoryId={}, commitSha={} is already READY. Skipping.", repositoryId, commitSha);
            return;
        }

        IndexingContext context = new TransactionTemplate(transactionManager).execute(status -> {
            RepositoryAiSettings settings = repositoryAiSettingsService.getRequired(repositoryId);
            GithubAccount account = settings.getPostingAccount();
            if (account == null) {
                account = settings.getWebhookRegisteredBy();
            }
            if (account == null) {
                throw new IllegalStateException("No GithubAccount configured for repository: " + repositoryId);
            }
            String decryptedToken = tokenEncryptionService.decryptToken(account.getAccessToken());
            return new IndexingContext(settings.getOwner(), settings.getRepositoryName(), decryptedToken);
        });

        if (context == null) {
            throw new IllegalStateException("Failed to load repository settings or account information");
        }

        String accessToken = context.accessToken;
        String owner = context.owner;
        String repo = context.repo;

        Map<String, List<String>> fileToRawImports = new HashMap<>();
        Set<String> allFiles = new HashSet<>();

        try (InputStream bodyStream = downloadZipball(owner, repo, commitSha, accessToken)) {
            parseZipStream(bodyStream, allFiles, fileToRawImports);
        }

        List<CodeFileDependency> dependencies = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : fileToRawImports.entrySet()) {
            String sourceFile = entry.getKey();
            List<String> rawImports = entry.getValue();

            for (String rawImport : rawImports) {
                List<String> resolvedTargets = resolveImport(sourceFile, rawImport, allFiles);
                if (resolvedTargets.isEmpty()) {
                    dependencies.add(CodeFileDependency.builder()
                            .codeGraphIndex(index)
                            .sourceFilePath(sourceFile)
                            .targetDependencyPath(null)
                            .rawImport(rawImport)
                            .relationType(RelationType.IMPORT)
                            .resolutionStatus(ResolutionStatus.UNRESOLVED)
                            .confidenceScore(0.5)
                            .build());
                } else {
                    for (String target : resolvedTargets) {
                        dependencies.add(CodeFileDependency.builder()
                                .codeGraphIndex(index)
                                .sourceFilePath(sourceFile)
                                .targetDependencyPath(target)
                                .rawImport(rawImport)
                                .relationType(RelationType.IMPORT)
                                .resolutionStatus(ResolutionStatus.RESOLVED)
                                .confidenceScore(1.0)
                                .build());
                    }
                }
            }
        }

        new TransactionTemplate(transactionManager).execute(status -> {
            saveIndexAndDependencies(index.getId(), allFiles.size(), dependencies);
            if (defaultBranch) {
                markOtherIndexesStale(repositoryId, index.getId());
            }
            return null;
        });
    }

    InputStream downloadZipball(String owner, String repo, String commitSha, String accessToken) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + owner + "/" + repo + "/zipball/" + commitSha))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .timeout(java.time.Duration.ofSeconds(120))
                .GET()
                .build();

        log.info("Downloading zipball from GitHub for repository {}/{} and commit {}", owner, repo, commitSha);
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            if (response.body() != null) {
                try {
                    response.body().close();
                } catch (IOException ignored) {}
            }
            throw new IOException("Failed to download zipball from GitHub. HTTP status: " + response.statusCode());
        }
        return response.body();
    }

    void parseZipStream(InputStream inputStream, Set<String> allFiles, Map<String, List<String>> fileToRawImports) throws Exception {
        long totalBytesRead = 0;
        int extractedFilesCount = 0;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zis = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Indexing thread interrupted.");
                }

                String entryName = entry.getName();

                if (isZipSlip(entryName)) {
                    throw new SecurityException("Zip Slip security violation: invalid entry name " + entryName);
                }

                if (entry.isDirectory()) {
                    continue;
                }

                String relativePath = stripRootDirectory(entryName);
                if (relativePath.isEmpty()) {
                    continue;
                }

                if (!isEligibleExtension(relativePath)) {
                    continue;
                }

                extractedFilesCount++;
                if (extractedFilesCount > maxExtractedFiles) {
                    throw new IllegalStateException("Maximum extracted files limit exceeded: " + maxExtractedFiles);
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                int read;
                long fileBytesRead = 0;
                while ((read = zis.read(buffer)) != -1) {
                    fileBytesRead += read;
                    totalBytesRead += read;

                    if (totalBytesRead > maxArchiveBytes) {
                        throw new IllegalStateException("Maximum archive size limit exceeded: " + maxArchiveBytes);
                    }
                    if (fileBytesRead > maxSourceFileBytes) {
                        break;
                    }
                    baos.write(buffer, 0, read);
                }

                if (fileBytesRead <= maxSourceFileBytes) {
                    String content = baos.toString(StandardCharsets.UTF_8);
                    allFiles.add(relativePath);

                    List<String> rawImports = parseRawImports(content, relativePath);
                    if (!rawImports.isEmpty()) {
                        fileToRawImports.put(relativePath, rawImports);
                    }
                }
            }
        }
    }

    private String stripRootDirectory(String path) {
        int firstSlash = path.indexOf('/');
        if (firstSlash == -1 || firstSlash == path.length() - 1) {
            return "";
        }
        return path.substring(firstSlash + 1);
    }

    private boolean isEligibleExtension(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".java") ||
                lower.endsWith(".js") ||
                lower.endsWith(".ts") ||
                lower.endsWith(".jsx") ||
                lower.endsWith(".tsx") ||
                lower.endsWith(".py") ||
                lower.endsWith(".go") ||
                lower.endsWith(".rs") ||
                lower.endsWith(".c") ||
                lower.endsWith(".cpp") ||
                lower.endsWith(".h") ||
                lower.endsWith(".hpp") ||
                lower.endsWith(".cs") ||
                lower.endsWith(".rb") ||
                lower.endsWith(".php") ||
                lower.endsWith(".json") ||
                lower.endsWith(".yml") ||
                lower.endsWith(".yaml") ||
                lower.endsWith(".xml") ||
                lower.endsWith(".gradle") ||
                lower.endsWith(".properties");
    }

    static List<String> parseRawImports(String content, String relativePath) {
        List<String> rawImports = new ArrayList<>();
        String extension = getExtension(relativePath);

        if ("java".equals(extension)) {
            String stripped = stripCommentsJava(content);
            Matcher matcher = JAVA_IMPORT_PATTERN.matcher(stripped);
            while (matcher.find()) {
                String imp = matcher.group(1);
                if (imp != null && !imp.isBlank()) {
                    rawImports.add(imp);
                }
            }
        } else if (List.of("js", "ts", "jsx", "tsx").contains(extension)) {
            String stripped = stripCommentsJava(content);
            Matcher matcher = JS_IMPORT_PATTERN_1.matcher(stripped);
            while (matcher.find()) {
                String imp = matcher.group(1);
                if (imp != null && !imp.isBlank()) {
                    rawImports.add(imp);
                }
            }
            matcher = JS_IMPORT_PATTERN_2.matcher(stripped);
            while (matcher.find()) {
                String imp = matcher.group(1);
                if (imp != null && !imp.isBlank()) {
                    rawImports.add(imp);
                }
            }
            matcher = JS_REQUIRE_PATTERN.matcher(stripped);
            while (matcher.find()) {
                String imp = matcher.group(1);
                if (imp != null && !imp.isBlank()) {
                    rawImports.add(imp);
                }
            }
        }
        return rawImports;
    }

    static String getExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot == -1 || lastDot == path.length() - 1) {
            return "";
        }
        return path.substring(lastDot + 1).toLowerCase();
    }

    public static String stripCommentsJava(String content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean inSingleLineComment = false;
        boolean inMultiLineComment = false;
        boolean inString = false;
        char stringChar = 0;

        int len = content.length();
        for (int i = 0; i < len; i++) {
            char c = content.charAt(i);
            char next = (i + 1 < len) ? content.charAt(i + 1) : 0;

            if (inSingleLineComment) {
                if (c == '\n' || c == '\r') {
                    inSingleLineComment = false;
                    sb.append(c);
                }
            } else if (inMultiLineComment) {
                if (c == '*' && next == '/') {
                    inMultiLineComment = false;
                    i++;
                }
            } else if (inString) {
                sb.append(c);
                if (c == '\\') {
                    if (i + 1 < len) {
                        sb.append(content.charAt(i + 1));
                        i++;
                    }
                } else if (c == stringChar) {
                    inString = false;
                }
            } else {
                if (c == '/' && next == '/') {
                    inSingleLineComment = true;
                    i++;
                } else if (c == '/' && next == '*') {
                    inMultiLineComment = true;
                    i++;
                } else {
                    sb.append(c);
                    if (c == '"' || c == '\'') {
                        inString = true;
                        stringChar = c;
                    }
                }
            }
        }
        return sb.toString();
    }

    static List<String> resolveImport(String sourceFile, String rawImport, Set<String> allFiles) {
        String extension = getExtension(sourceFile);
        if ("java".equals(extension)) {
            return resolveJavaImport(rawImport, allFiles);
        } else if (List.of("js", "ts", "jsx", "tsx").contains(extension)) {
            return resolveJsTsImport(sourceFile, rawImport, allFiles);
        }
        return List.of();
    }

    static List<String> resolveJavaImport(String importStr, Set<String> allFiles) {
        List<String> resolved = new ArrayList<>();
        if (importStr.endsWith(".*")) {
            String packagePath = importStr.substring(0, importStr.length() - 2).replace('.', '/');
            for (String file : allFiles) {
                if (file.endsWith(".java") && file.contains("/" + packagePath + "/")) {
                    resolved.add(file);
                }
            }
        } else {
            String filePathSuffix = importStr.replace('.', '/') + ".java";
            for (String file : allFiles) {
                if (file.endsWith(filePathSuffix)) {
                    resolved.add(file);
                    break;
                }
            }
        }
        return resolved;
    }

    static List<String> resolveJsTsImport(String sourceFile, String importStr, Set<String> allFiles) {
        List<String> resolved = new ArrayList<>();

        if (importStr.contains("?")) {
            importStr = importStr.substring(0, importStr.indexOf('?'));
        }

        String[] extensions = {".ts", ".tsx", ".js", ".jsx", "/index.ts", "/index.tsx", "/index.js", "/index.jsx"};
        List<String> candidatesToTry = new ArrayList<>();

        if (!importStr.startsWith(".")) {
            if (importStr.startsWith("@/")) {
                candidatesToTry.add("src/" + importStr.substring(2));
            } else if (importStr.startsWith("src/")) {
                candidatesToTry.add(importStr);
            } else {
                candidatesToTry.add("src/" + importStr);
                candidatesToTry.add(importStr);
            }
        } else {
            String dir = directoryOf(sourceFile);
            candidatesToTry.add(normalizeRelativePath(dir, importStr));
        }

        for (String baseCandidate : candidatesToTry) {
            for (String ext : extensions) {
                String candidate = baseCandidate + ext;
                if (allFiles.contains(candidate)) {
                    resolved.add(candidate);
                    break;
                }
            }
            if (!resolved.isEmpty()) {
                break;
            }
        }
        return resolved;
    }

    static String directoryOf(String path) {
        if (path == null || !path.contains("/")) {
            return "";
        }
        return path.substring(0, path.lastIndexOf('/'));
    }

    static String normalizeRelativePath(String baseDir, String relativePath) {
        String path = baseDir.isEmpty() ? relativePath : baseDir + "/" + relativePath;
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    private CodeGraphIndex createOrGetIndexingState(Long repositoryId, String refName, String commitSha, boolean defaultBranch) {
        Optional<CodeGraphIndex> existingOpt = codeGraphIndexRepository.findByRepositoryIdAndCommitSha(repositoryId, commitSha);
        if (existingOpt.isPresent()) {
            CodeGraphIndex existing = existingOpt.get();
            if (existing.getStatus() != CodeGraphIndex.Status.READY) {
                existing.updateStatus(CodeGraphIndex.Status.INDEXING);
                return codeGraphIndexRepository.save(existing);
            }
            return existing;
        }

        CodeGraphIndex newIndex = CodeGraphIndex.builder()
                .repositoryId(repositoryId)
                .refName(refName)
                .commitSha(commitSha)
                .status(CodeGraphIndex.Status.INDEXING)
                .defaultBranch(defaultBranch)
                .startedAt(LocalDateTime.now())
                .build();
        return codeGraphIndexRepository.save(newIndex);
    }

    private void saveIndexAndDependencies(Long indexId, int fileCount, List<CodeFileDependency> dependencies) {
        CodeGraphIndex index = codeGraphIndexRepository.findById(indexId)
                .orElseThrow(() -> new IllegalStateException("Index entry not found: " + indexId));

        codeFileDependencyRepository.deleteByCodeGraphIndexId(indexId);
        codeFileDependencyRepository.saveAll(dependencies);

        index.markReady(fileCount, dependencies.size(), "JVM_REGEX", "1.0");
        codeGraphIndexRepository.save(index);
    }

    private void markOtherIndexesStale(Long repositoryId, Long currentIndexId) {
        List<CodeGraphIndex> indexes = codeGraphIndexRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId);
        for (CodeGraphIndex idx : indexes) {
            if (!idx.getId().equals(currentIndexId)) {
                codeFileDependencyRepository.deleteByCodeGraphIndexId(idx.getId());
                codeGraphIndexRepository.delete(idx);
            }
        }
    }

    private void markIndexFailed(Long repositoryId, String commitSha, String reason) {
        new TransactionTemplate(transactionManager).execute(status -> {
            Optional<CodeGraphIndex> indexOpt = codeGraphIndexRepository.findByRepositoryIdAndCommitSha(repositoryId, commitSha);
            if (indexOpt.isPresent()) {
                CodeGraphIndex index = indexOpt.get();
                index.markFailed(reason);
                codeGraphIndexRepository.save(index);
            } else {
                CodeGraphIndex index = CodeGraphIndex.builder()
                        .repositoryId(repositoryId)
                        .refName("unknown")
                        .commitSha(commitSha)
                        .status(CodeGraphIndex.Status.FAILED)
                        .failureReason(reason)
                        .completedAt(LocalDateTime.now())
                        .build();
                codeGraphIndexRepository.save(index);
            }
            return null;
        });
    }

    static boolean isZipSlip(String entryName) {
        if (entryName == null) {
            return false;
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            return true;
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static class IndexingContext {
        final String owner;
        final String repo;
        final String accessToken;

        IndexingContext(String owner, String repo, String accessToken) {
            this.owner = owner;
            this.repo = repo;
            this.accessToken = accessToken;
        }
    }
}
