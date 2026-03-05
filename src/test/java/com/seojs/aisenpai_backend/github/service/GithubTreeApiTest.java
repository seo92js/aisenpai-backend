package com.seojs.aisenpai_backend.github.service;

import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class GithubTreeApiTest {

    @Autowired
    private GithubService githubService;

    @Test
    @Disabled
    @DisplayName("GitHub Tree API를 호출하여 저장소의 파일 구조 목록을 가져올 수 있다")
    void getRepositoryTree_success() {
        // given
        String accessToken = "";
        String owner = "seo92js";
        String repo = "aisenpai-frontend";
        String branch = "main";

        if (accessToken == null || accessToken.isBlank()) {
            System.out.println("GITHUB_TEST_TOKEN 환경 변수가 없어 테스트를 스킵합니다.");
            return;
        }

        // when
        GitTreeResponseDto treeResponse = githubService.getRepositoryTree(accessToken, owner, repo, branch, true);

        // then
        assertThat(treeResponse).isNotNull();
        assertThat(treeResponse.getTree()).isNotEmpty();

        System.out.println("가져온 항목 수: " + treeResponse.getTree().size());

        boolean hasFile = treeResponse.getTree().stream()
                .anyMatch(item -> "blob".equals(item.getType()));
        assertThat(hasFile).isTrue();
        treeResponse.getTree().stream()
                .limit(10)
                .forEach(item -> System.out.println(item.getType() + " - " + item.getPath()));
    }
}
