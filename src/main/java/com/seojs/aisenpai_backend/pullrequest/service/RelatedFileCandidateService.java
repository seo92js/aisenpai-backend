package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ChangedFileContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ContentFetchStatus;
import org.springframework.stereotype.Service;

import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@lombok.extern.slf4j.Slf4j
public class RelatedFileCandidateService {
    public static final int MAX_RELATED_FILES = 6;
    public static final int MAX_RELATED_FILES_PER_CHANGED_FILE = 2;

    private final CodeGraphQueryService codeGraphQueryService;

    @org.springframework.beans.factory.annotation.Autowired
    public RelatedFileCandidateService(CodeGraphQueryService codeGraphQueryService) {
        this.codeGraphQueryService = codeGraphQueryService;
    }

    public RelatedFileCandidateService() {
        this.codeGraphQueryService = null;
    }

    private static final List<String> CONFIG_FILENAMES = List.of(
            "package.json", "tsconfig.json", "pyproject.toml", "go.mod", "Cargo.toml", "pom.xml",
            "build.gradle", "Dockerfile");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
            "(?:import|export|require|include|from)\\s*(?:\\([^'\"]*)?[\"']([^\"']+)[\"']");

    public List<RelatedFileCandidate> findCandidates(List<ChangedFileContextDto> changedFiles,
            GitTreeResponseDto treeDto, List<String> ignorePatterns) {
        return findCandidates(null, changedFiles, treeDto, ignorePatterns);
    }

    public List<RelatedFileCandidate> findCandidates(Long repositoryId, List<ChangedFileContextDto> changedFiles,
            GitTreeResponseDto treeDto, List<String> ignorePatterns) {
        if (changedFiles == null || changedFiles.isEmpty() || treeDto == null || treeDto.getTree() == null) {
            return List.of();
        }

        List<String> treePaths = treeDto.getTree().stream()
                .filter(item -> "blob".equals(item.getType()))
                .map(GitTreeResponseDto.GitTreeItemDto::getPath)
                .filter(path -> path != null && !path.isBlank())
                .toList();
        if (treePaths.isEmpty()) {
            return List.of();
        }

        List<RelatedFileCandidate> boosterCandidates = List.of();
        if (codeGraphQueryService != null && repositoryId != null) {
            try {
                boosterCandidates = codeGraphQueryService.findCandidates(repositoryId, changedFiles, treePaths, ignorePatterns);
                log.info("Graph booster found {} candidates for repositoryId={}", boosterCandidates.size(), repositoryId);
            } catch (Exception e) {
                log.warn("Graph booster failed for repositoryId={}. Falling back to regex search. Error: {}", repositoryId, e.getMessage());
            }
        }

        Map<String, RelatedFileCandidate> selected = new LinkedHashMap<>();
        for (RelatedFileCandidate bc : boosterCandidates) {
            if (selected.size() >= MAX_RELATED_FILES) {
                break;
            }
            selected.put(bc.path(), bc);
        }

        if (selected.size() < MAX_RELATED_FILES) {
            List<RelatedFileCandidate> fallbackCandidates = runFallbackSearch(changedFiles, treePaths, ignorePatterns);
            for (RelatedFileCandidate fc : fallbackCandidates) {
                if (selected.size() >= MAX_RELATED_FILES) {
                    break;
                }
                selected.putIfAbsent(fc.path(), fc);
            }
        }

        return List.copyOf(selected.values());
    }

    private List<RelatedFileCandidate> runFallbackSearch(List<ChangedFileContextDto> changedFiles,
            List<String> treePaths, List<String> ignorePatterns) {
        Set<String> changedPaths = new HashSet<>();
        for (ChangedFileContextDto changedFile : changedFiles) {
            if (changedFile.getFilename() != null) {
                changedPaths.add(changedFile.getFilename());
            }
        }

        List<PathMatcher> ignoreMatchers = ReviewPathUtils.buildIgnoreMatchers(ignorePatterns);
        Map<String, RelatedFileCandidate> selected = new LinkedHashMap<>();
        for (ChangedFileContextDto changedFile : changedFiles) {
            if (selected.size() >= MAX_RELATED_FILES) {
                break;
            }
            if (!isEligibleChangedFileSource(changedFile)) {
                continue;
            }

            int addedForFile = 0;
            List<RelatedFileCandidate> candidates = new ArrayList<>();
            candidates.addAll(findReferenceHintCandidates(changedFile, treePaths));
            candidates.addAll(findBasenameCandidates(changedFile, treePaths));
            candidates.addAll(findDirectoryProximityCandidates(changedFile, treePaths));
            candidates.addAll(findConfigCandidates(changedFile, treePaths));

            for (RelatedFileCandidate candidate : candidates) {
                if (selected.size() >= MAX_RELATED_FILES || addedForFile >= MAX_RELATED_FILES_PER_CHANGED_FILE) {
                    break;
                }
                if (isExcluded(candidate.path(), changedPaths, ignoreMatchers)) {
                    continue;
                }
                if (!selected.containsKey(candidate.path())) {
                    selected.put(candidate.path(), candidate);
                    addedForFile++;
                }
            }
        }

        return List.copyOf(selected.values());
    }

    private List<RelatedFileCandidate> findReferenceHintCandidates(ChangedFileContextDto changedFile,
            List<String> treePaths) {
        if (changedFile.getHeadContent() == null || changedFile.getHeadContent().isBlank()) {
            return List.of();
        }

        List<RelatedFileCandidate> candidates = new ArrayList<>();
        Matcher matcher = REFERENCE_PATTERN.matcher(changedFile.getHeadContent());
        String currentDirectory = directoryOf(changedFile.getFilename());
        while (matcher.find()) {
            String reference = matcher.group(1);
            if (reference == null || reference.isBlank()) {
                continue;
            }
            findTreePathForReference(reference, currentDirectory, treePaths)
                    .stream()
                    .map(path -> new RelatedFileCandidate(path, "reference hint from " + changedFile.getFilename()))
                    .forEach(candidates::add);
        }
        return candidates;
    }

    private List<String> findTreePathForReference(String reference, String currentDirectory, List<String> treePaths) {
        String normalizedReference = reference.replace("\\", "/");
        if (isBareExternalReference(normalizedReference)) {
            return List.of();
        }

        List<String> possiblePrefixes = new ArrayList<>();
        if (normalizedReference.startsWith(".")) {
            possiblePrefixes.add(normalizePath(currentDirectory + "/" + normalizedReference));
        } else {
            possiblePrefixes.add(normalizedReference);
            possiblePrefixes.add(normalizedReference.replace('.', '/'));
        }

        return treePaths.stream()
                .filter(path -> possiblePrefixes.stream().anyMatch(prefix -> matchesReference(path, prefix)))
                .toList();
    }

    private boolean matchesReference(String path, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        return path.equals(prefix)
                || path.startsWith(prefix + ".")
                || path.startsWith(prefix + "/")
                || path.endsWith("/" + prefix)
                || path.matches(".*/" + Pattern.quote(prefix) + "\\.[^/]+$");
    }

    private List<RelatedFileCandidate> findBasenameCandidates(ChangedFileContextDto changedFile,
            List<String> treePaths) {
        String filename = changedFile.getFilename();
        String basename = ReviewPathUtils.basenameWithoutExtension(filename);
        if (basename.isBlank()) {
            return List.of();
        }

        return treePaths.stream()
                .filter(path -> !path.equals(filename))
                .filter(path -> ReviewPathUtils.basenameWithoutExtension(path).equals(basename)
                        || ReviewPathUtils.basenameWithoutExtension(path).startsWith(basename + "-")
                        || ReviewPathUtils.basenameWithoutExtension(path).startsWith(basename + "."))
                .map(path -> new RelatedFileCandidate(path, "same basename as " + filename))
                .toList();
    }

    private List<RelatedFileCandidate> findDirectoryProximityCandidates(ChangedFileContextDto changedFile,
            List<String> treePaths) {
        String filename = changedFile.getFilename();
        String directory = directoryOf(filename);
        if (directory.isBlank()) {
            return List.of();
        }

        return treePaths.stream()
                .filter(path -> !path.equals(filename))
                .filter(path -> directory.equals(directoryOf(path)))
                .filter(this::isDirectoryContextPath)
                .map(path -> new RelatedFileCandidate(path, "same directory as " + filename))
                .toList();
    }

    private List<RelatedFileCandidate> findConfigCandidates(ChangedFileContextDto changedFile, List<String> treePaths) {
        String filename = changedFile.getFilename();
        if (!isConfigPath(filename)) {
            return List.of();
        }

        return treePaths.stream()
                .filter(path -> !path.equals(filename))
                .filter(this::isConfigPath)
                .map(path -> new RelatedFileCandidate(path, "near configuration change " + filename))
                .toList();
    }

    private boolean isExcluded(String path, Set<String> changedPaths, List<PathMatcher> ignoreMatchers) {
        return changedPaths.contains(path)
                || ReviewPathUtils.isBinaryPath(path)
                || ReviewPathUtils.isGeneratedOrVendorPath(path)
                || ReviewPathUtils.isTestPath(path)
                || matchesIgnorePattern(path, ignoreMatchers);
    }

    private boolean isEligibleChangedFileSource(ChangedFileContextDto changedFile) {
        return changedFile != null
                && changedFile.getFilename() != null
                && changedFile.getContentFetchStatus() == ContentFetchStatus.FETCHED;
    }

    private boolean isBareExternalReference(String reference) {
        return !reference.startsWith(".")
                && !reference.startsWith("/")
                && !reference.contains("/")
                && !reference.contains(".");
    }

    private boolean isConfigPath(String path) {
        String name = ReviewPathUtils.filenameOf(path);
        return CONFIG_FILENAMES.contains(name) || path.startsWith(".github/workflows/");
    }

    private boolean isDirectoryContextPath(String path) {
        String lower = ReviewPathUtils.filenameOf(path).toLowerCase();
        return lower.endsWith(".css")
                || lower.endsWith(".scss")
                || lower.endsWith(".sass")
                || lower.endsWith(".less")
                || lower.contains(".module.")
                || lower.contains(".types.")
                || lower.contains(".type.")
                || lower.contains(".schema.")
                || lower.contains(".contract.")
                || lower.contains(".interface.")
                || isConfigPath(path);
    }

    private boolean matchesIgnorePattern(String filename, List<PathMatcher> ignoreMatchers) {
        return ignoreMatchers.stream().anyMatch(matcher -> matcher.matches(Paths.get(filename)));
    }

    private String directoryOf(String path) {
        if (path == null || !path.contains("/")) {
            return "";
        }
        return path.substring(0, path.lastIndexOf('/'));
    }

    private String normalizePath(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    public record RelatedFileCandidate(String path, String reason) {
    }
}
