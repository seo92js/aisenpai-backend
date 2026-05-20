package com.seojs.aisenpai_backend.pullrequest.service;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;

final class ReviewPathUtils {
    private static final List<String> GENERATED_OR_VENDOR_PATH_PARTS = List.of(
            "/node_modules/", "/vendor/", "/dist/", "/build/", "/target/", "/coverage/", "/.next/", "/out/");
    private static final List<String> BINARY_EXTENSIONS = List.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".gz", ".tar", ".jar",
            ".class", ".wasm", ".woff", ".woff2", ".ttf", ".eot", ".mp4", ".mov", ".avi", ".mp3");

    private ReviewPathUtils() {
    }

    static boolean isGeneratedOrVendorPath(String path) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return GENERATED_OR_VENDOR_PATH_PARTS.stream().anyMatch(normalizedPath::contains);
    }

    static boolean isBinaryPath(String filename) {
        String lower = filename.toLowerCase();
        return BINARY_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    static boolean isTestPath(String path) {
        String lower = path.toLowerCase();
        String normalized = "/" + lower;
        String name = filenameOf(lower);
        String basename = basenameWithoutExtension(name);
        return normalized.contains("/test/")
                || normalized.contains("/tests/")
                || normalized.contains("/spec/")
                || normalized.contains("/__tests__/")
                || name.contains(".test.")
                || name.contains(".spec.")
                || name.startsWith("test_")
                || basename.endsWith("_test");
    }

    static List<PathMatcher> buildIgnoreMatchers(List<String> ignorePatterns) {
        if (ignorePatterns == null || ignorePatterns.isEmpty()) {
            return List.of();
        }
        return ignorePatterns.stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .map(ReviewPathUtils::convertUserPatternToGlob)
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }

    static String filenameOf(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    static String basenameWithoutExtension(String path) {
        String filename = filenameOf(path);
        int dot = filename.indexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static String convertUserPatternToGlob(String pattern) {
        pattern = pattern.trim();
        boolean isDirectory = pattern.endsWith("/");
        if (isDirectory) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }

        boolean isRooted = pattern.startsWith("/");
        if (isRooted) {
            pattern = pattern.substring(1);
        }

        boolean hasSlash = pattern.contains("/");
        StringBuilder glob = new StringBuilder();
        if (!isRooted && !hasSlash) {
            glob.append("{**/,}");
        }
        glob.append(pattern);
        if (isDirectory) {
            glob.append("/**");
        } else {
            glob.append("{,/**}");
        }
        return glob.toString();
    }
}
