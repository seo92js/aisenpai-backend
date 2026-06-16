package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ChangedFileContextDto;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeFileDependency;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeGraphIndex;
import com.seojs.aisenpai_backend.pullrequest.repository.CodeFileDependencyRepository;
import com.seojs.aisenpai_backend.pullrequest.repository.CodeGraphIndexRepository;
import com.seojs.aisenpai_backend.pullrequest.service.RelatedFileCandidateService.RelatedFileCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@Service
public class CodeGraphQueryService {

    private final CodeGraphIndexRepository codeGraphIndexRepository;
    private final CodeFileDependencyRepository codeFileDependencyRepository;



    @Transactional(readOnly = true)
    public List<RelatedFileCandidate> findCandidates(
            Long repositoryId,
            List<ChangedFileContextDto> changedFiles,
            List<String> treePaths,
            List<String> ignorePatterns) {

        Optional<CodeGraphIndex> baselineIndexOpt = codeGraphIndexRepository
                .findFirstByRepositoryIdAndDefaultBranchAndStatusOrderByCreatedAtDesc(
                        repositoryId, true, CodeGraphIndex.Status.READY);

        if (baselineIndexOpt.isEmpty()) {
            log.info("No READY baseline index found for repositoryId={}. Skipping graph query.", repositoryId);
            return List.of();
        }

        CodeGraphIndex baselineIndex = baselineIndexOpt.get();
        List<CodeFileDependency> dbDeps = codeFileDependencyRepository.findByCodeGraphIndexId(baselineIndex.getId());

        Map<String, Set<DependencyEdge>> outboundGraph = new HashMap<>();
        Map<String, Set<DependencyEdge>> inboundGraph = new HashMap<>();

        for (CodeFileDependency dep : dbDeps) {
            String src = dep.getSourceFilePath();
            String tgt = dep.getTargetDependencyPath();
            double score = dep.getConfidenceScore() != null ? dep.getConfidenceScore() : 1.0;
            boolean resolved = dep.getResolutionStatus() == CodeFileDependency.ResolutionStatus.RESOLVED;

            DependencyEdge edge = new DependencyEdge(dep.getRawImport(), tgt, resolved, score);
            outboundGraph.computeIfAbsent(src, k -> new HashSet<>()).add(edge);
            if (resolved && tgt != null) {
                inboundGraph.computeIfAbsent(tgt, k -> new HashSet<>()).add(new DependencyEdge(dep.getRawImport(), src, true, score));
            }
        }

        Set<String> allFiles = new HashSet<>(treePaths);
        Set<String> changedPaths = new HashSet<>();
        for (ChangedFileContextDto f : changedFiles) {
            if (f.getFilename() != null) {
                changedPaths.add(f.getFilename());
            }
        }

        for (ChangedFileContextDto f : changedFiles) {
            String filename = f.getFilename();
            String status = f.getStatus();
            String prevFilename = f.getPreviousFilename();

            if ("removed".equals(status)) {
                outboundGraph.remove(filename);
                inboundGraph.remove(filename);
                breakInboundEdgesTo(filename, outboundGraph);
            } 
            else if ("renamed".equals(status) && prevFilename != null) {
                Set<DependencyEdge> outEdges = outboundGraph.remove(prevFilename);
                if (outEdges != null) {
                    outboundGraph.put(filename, outEdges);
                }
                Set<DependencyEdge> inEdges = inboundGraph.remove(prevFilename);
                if (inEdges != null) {
                    inboundGraph.put(filename, inEdges);
                }
                renameTargetInOutboundGraph(prevFilename, filename, outboundGraph, inboundGraph);
            } 
            else if ("modified".equals(status) || "added".equals(status)) {
                if (f.getHeadContent() != null && !f.getHeadContent().isBlank()) {
                    List<String> rawImports = CodeGraphIndexService.parseRawImports(f.getHeadContent(), filename);
                    Set<DependencyEdge> newOutEdges = new HashSet<>();
                    for (String rawImport : rawImports) {
                        List<String> resolved = CodeGraphIndexService.resolveImport(filename, rawImport, allFiles);
                        if (resolved.isEmpty()) {
                            newOutEdges.add(new DependencyEdge(rawImport, null, false, 0.5));
                        } else {
                            for (String tgt : resolved) {
                                newOutEdges.add(new DependencyEdge(rawImport, tgt, true, 1.0));
                            }
                        }
                    }
                    outboundGraph.put(filename, newOutEdges);

                    removeInboundEdgesFrom(filename, inboundGraph);
                    for (DependencyEdge edge : newOutEdges) {
                        if (edge.resolved && edge.targetPath != null) {
                            inboundGraph.computeIfAbsent(edge.targetPath, k -> new HashSet<>())
                                    .add(new DependencyEdge(edge.rawImport, filename, true, edge.confidenceScore));
                        }
                    }
                }
            }
        }

        List<PathMatcher> ignoreMatchers = ReviewPathUtils.buildIgnoreMatchers(ignorePatterns);
        Map<String, CandidateWithScore> candidates = new HashMap<>();

        for (ChangedFileContextDto f : changedFiles) {
            String filename = f.getFilename();
            if (filename == null || "removed".equals(f.getStatus())) {
                continue;
            }

            Set<DependencyEdge> outEdges = outboundGraph.get(filename);
            if (outEdges != null) {
                for (DependencyEdge edge : outEdges) {
                    if (edge.resolved && edge.targetPath != null) {
                        addCandidate(candidates, edge.targetPath, edge.confidenceScore * 1.0, 
                                "imports " + edge.targetPath + " (graph booster)", changedPaths, ignoreMatchers);
                    }
                }
            }

            Set<DependencyEdge> inEdges = inboundGraph.get(filename);
            if (inEdges != null) {
                for (DependencyEdge edge : inEdges) {
                    if (edge.resolved && edge.targetPath != null) {
                        addCandidate(candidates, edge.targetPath, edge.confidenceScore * 0.8, 
                                "imported by " + edge.targetPath + " (graph booster)", changedPaths, ignoreMatchers);
                    }
                }
            }
        }

        List<CandidateWithScore> sortedList = new ArrayList<>(candidates.values());
        sortedList.sort((c1, c2) -> Double.compare(c2.score, c1.score));

        List<RelatedFileCandidate> result = new ArrayList<>();
        for (CandidateWithScore c : sortedList) {
            result.add(new RelatedFileCandidate(c.path, c.reason));
        }

        return result;
    }

    private void addCandidate(Map<String, CandidateWithScore> candidates, String path, double score, String reason,
                              Set<String> changedPaths, List<PathMatcher> ignoreMatchers) {
        if (changedPaths.contains(path)) return;
        if (ReviewPathUtils.isBinaryPath(path) || ReviewPathUtils.isGeneratedOrVendorPath(path) || ReviewPathUtils.isTestPath(path)) return;
        if (matchesIgnorePattern(path, ignoreMatchers)) return;

        candidates.compute(path, (k, existing) -> {
            if (existing == null || score > existing.score) {
                return new CandidateWithScore(path, score, reason);
            }
            return existing;
        });
    }

    private boolean matchesIgnorePattern(String filename, List<PathMatcher> ignoreMatchers) {
        return ignoreMatchers.stream().anyMatch(matcher -> matcher.matches(Paths.get(filename)));
    }

    private void breakInboundEdgesTo(String targetPath, Map<String, Set<DependencyEdge>> outboundGraph) {
        for (Map.Entry<String, Set<DependencyEdge>> entry : outboundGraph.entrySet()) {
            Set<DependencyEdge> edges = entry.getValue();
            for (DependencyEdge edge : edges) {
                if (targetPath.equals(edge.targetPath)) {
                    edge.targetPath = null;
                    edge.resolved = false;
                    edge.confidenceScore = 0.0;
                }
            }
        }
    }

    private void removeInboundEdgesFrom(String sourcePath, Map<String, Set<DependencyEdge>> inboundGraph) {
        for (Map.Entry<String, Set<DependencyEdge>> entry : inboundGraph.entrySet()) {
            Set<DependencyEdge> edges = entry.getValue();
            edges.removeIf(edge -> sourcePath.equals(edge.targetPath));
        }
    }

    private void renameTargetInOutboundGraph(String oldPath, String newPath, 
                                             Map<String, Set<DependencyEdge>> outboundGraph,
                                             Map<String, Set<DependencyEdge>> inboundGraph) {
        for (Map.Entry<String, Set<DependencyEdge>> entry : outboundGraph.entrySet()) {
            Set<DependencyEdge> edges = entry.getValue();
            for (DependencyEdge edge : edges) {
                if (oldPath.equals(edge.targetPath)) {
                    edge.targetPath = newPath;
                    edge.confidenceScore = edge.confidenceScore * 0.8;
                    
                    Set<DependencyEdge> inEdges = inboundGraph.get(newPath);
                    if (inEdges != null) {
                        for (DependencyEdge inEdge : inEdges) {
                            if (entry.getKey().equals(inEdge.targetPath)) {
                                inEdge.confidenceScore = inEdge.confidenceScore * 0.8;
                            }
                        }
                    }
                }
            }
        }
    }



    private static class DependencyEdge {
        String rawImport;
        String targetPath;
        boolean resolved;
        double confidenceScore;

        DependencyEdge(String rawImport, String targetPath, boolean resolved, double confidenceScore) {
            this.rawImport = rawImport;
            this.targetPath = targetPath;
            this.resolved = resolved;
            this.confidenceScore = confidenceScore;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DependencyEdge that)) return false;
            return resolved == that.resolved &&
                    Double.compare(that.confidenceScore, confidenceScore) == 0 &&
                    Objects.equals(rawImport, that.rawImport) &&
                    Objects.equals(targetPath, that.targetPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(rawImport, targetPath, resolved, confidenceScore);
        }
    }

    private static class CandidateWithScore {
        String path;
        double score;
        String reason;

        CandidateWithScore(String path, double score, String reason) {
            this.path = path;
            this.score = score;
            this.reason = reason;
        }
    }
}
