-- Existing H2 or MySQL databases: apply once before using the community update.
ALTER TABLE board_posts ADD COLUMN author_member_id BIGINT;
ALTER TABLE board_posts ADD COLUMN author_name VARCHAR(20);
ALTER TABLE board_posts ADD COLUMN school_name VARCHAR(100);
CREATE TABLE if not EXISTS board_comments (
    comment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    author_name VARCHAR(20) NOT NULL,
    school_name VARCHAR(100),
    content VARCHAR(1000) NOT NULL,
    regtime TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_comment_post FOREIGN KEY(post_id) REFERENCES board_posts(post_id) ON DELETE CASCADE
);
--CREATE INDEX ix_comments_post_time ON board_comments(post_id, comment_id);
