package com.seojs.aisenpai_backend.pullrequest.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest.PullRequestState;
import lombok.RequiredArgsConstructor;

import static com.seojs.aisenpai_backend.pullrequest.entity.QPullRequest.pullRequest;

@RequiredArgsConstructor
public class PullRequestRepositoryImpl implements PullRequestRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    @Override
    public boolean existsOpenPrByRepositoryId(Long repositoryId) {
        Integer result = queryFactory.selectOne()
                .from(pullRequest)
                .where(
                        pullRequest.repositoryId.eq(repositoryId),
                        pullRequest.prState.eq(PullRequestState.OPEN))
                .fetchFirst();

        return result != null;
    }
}
