package com.seojs.aisenpai_backend.github.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ReviewAnchorService {

    /**
     * Patch 내에서 codeSnippet이 위치한 라인의 GitHub 코멘트용 라인 번호 조회
     */
    public Integer findLineNumber(String patch, String codeSnippet) {
        return findAnchor(patch, codeSnippet).line();
    }

    public AnchorResult findAnchor(String patch, String codeSnippet) {
        if (codeSnippet == null || codeSnippet.isBlank()) {
            return AnchorResult.failed(AnchorFailureReason.EMPTY_SNIPPET);
        }
        if (patch == null || patch.isBlank()) {
            return AnchorResult.failed(AnchorFailureReason.MISSING_PATCH);
        }

        List<String> snippetLines = codeSnippet.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        if (snippetLines.isEmpty()) {
            return AnchorResult.failed(AnchorFailureReason.EMPTY_SNIPPET);
        }
        if (snippetLines.size() > 1) {
            return AnchorResult.failed(AnchorFailureReason.MULTILINE_SNIPPET_MISMATCH);
        }

        String targetLine = snippetLines.get(0);
        if (targetLine.isEmpty()) {
            return AnchorResult.failed(AnchorFailureReason.EMPTY_SNIPPET);
        }

        String[] patchLines = patch.split("\\R");
        int currentLineInFile = 0;

        for (String line : patchLines) {
            String trimmedLine = line.trim();

            if (trimmedLine.startsWith("@@")) {
                try {
                    String[] parts = trimmedLine.split("\\s+");
                    if (parts.length >= 3) {
                        String newInfo = parts[2];
                        String cleanNewInfo = newInfo.startsWith("+") ? newInfo.substring(1) : newInfo;
                        String startLineStr = cleanNewInfo.split(",")[0];

                        currentLineInFile = Integer.parseInt(startLineStr) - 1; // 0-based context
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse hunk header: '{}'. Error: {}", line, e.getMessage());
                    currentLineInFile = -1;
                }
                continue;
            }

            if (currentLineInFile == -1) {
                continue;
            }

            if (line.startsWith(" ")) {
                currentLineInFile++;
            } else if (line.startsWith("+")) {
                currentLineInFile++;
                // 추가된 라인만 GitHub inline comment 대상이다.
                String cleanLine = line.substring(1).trim();

                if (cleanLine.equals(targetLine)) {
                    return AnchorResult.anchored(currentLineInFile);
                }
            }
        }

        return AnchorResult.failed(AnchorFailureReason.SNIPPET_NOT_ADDED_LINE);
    }

    public enum AnchorFailureReason {
        EMPTY_SNIPPET,
        MISSING_PATCH,
        MULTILINE_SNIPPET_MISMATCH,
        SNIPPET_NOT_ADDED_LINE
    }

    public record AnchorResult(Integer line, AnchorFailureReason failureReason) {
        public static AnchorResult anchored(Integer line) {
            return new AnchorResult(line, null);
        }

        public static AnchorResult failed(AnchorFailureReason failureReason) {
            return new AnchorResult(null, failureReason);
        }

        public boolean anchored() {
            return line != null;
        }
    }
}
