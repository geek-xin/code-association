package com.geek.association.source;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 引用行所在代码块的边界。
 *
 * <p>这里测的不是"能不能找到花括号"，而是三件会悄悄给错上下文的事：控制块要不要继续向外扩、
 * 字符串与注释里的花括号算不算结构、XML 会不会把整个根元素当成一条语句贴出来。</p>
 */
class CodeBlocksTest {

    private static final int CAP = CodeExcerpts.MAX_BLOCK_LINES;

    @Test
    void aCallSiteResolvesToItsMethodNotJustTheIfItSitsIn() {
        String src = """
                class A {
                    void m() {
                        if (ready) {
                            call();
                        }
                    }
                }
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.java", lines(src), 4, CAP);

        assertEquals(2, block.start());
        assertEquals(6, block.end());
        assertEquals("方法", block.label());
    }

    @Test
    void theWholeMethodIsReturnedFromSignatureToClosingBrace() {
        String src = """
                class A {
                    void one() {
                        int a = 1;
                    }

                    void two() {
                        call();
                    }
                }
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.java", lines(src), 7, CAP);

        assertEquals(6, block.start());
        assertEquals(8, block.end());
        assertEquals("方法", block.label());
    }

    @Test
    void bracesInsideStringsAndCommentsDoNotCloseTheMethod() {
        String src = """
                class A {
                    void m() {
                        String s = "}";
                        // }
                        /* } */
                        call();
                    }
                }
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.java", lines(src), 6, CAP);

        assertEquals(2, block.start());
        assertEquals(7, block.end());
    }

    @Test
    void anElseBranchDoesNotBecomeTheContext() {
        String src = """
                class A {
                    void m() {
                        if (a) {
                            one();
                        } else {
                            two();
                        }
                    }
                }
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.java", lines(src), 6, CAP);

        assertEquals(2, block.start());
        assertEquals(8, block.end());
    }

    @Test
    void aLambdaBodyExpandsOutToTheMethod() {
        String src = """
                class A {
                    void m() {
                        list.forEach(x -> {
                            call();
                        });
                    }
                }
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.java", lines(src), 4, CAP);

        assertEquals(2, block.start());
        assertEquals(6, block.end());
    }

    @Test
    void aFieldInitializerFallsBackToTheTypeBody() {
        String src = """
                public class A {
                    private final int x = 1;
                }
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.java", lines(src), 2, CAP);

        assertEquals(1, block.start());
        assertEquals(3, block.end());
        assertEquals("类型", block.label());
    }

    @Test
    void aMultiLineSignatureStillReadsAsAMethod() {
        String src = """
                class A {
                    void m(
                            int a,
                            int b) {
                        call();
                    }
                }
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.java", lines(src), 5, CAP);

        assertEquals(2, block.start());
        assertEquals(6, block.end());
        assertEquals("方法", block.label());
    }

    @Test
    void aSqlStatementIsOneBlockEvenWhenItSpansManyLines() {
        String src = """
                -- 用户表
                create table t_user (
                    id bigint primary key,
                    name varchar(64) not null,
                    created_at timestamp
                );

                alter table t_user add column email varchar(128);
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("V1__init.sql", lines(src), 4, CAP);

        assertEquals(2, block.start());
        assertEquals(6, block.end());
        assertEquals("SQL 语句", block.label());
    }

    @Test
    void aSemicolonInsideAStringDoesNotEndTheStatement() {
        String src = """
                insert into t_log(message)
                values ('done; retry'
                  );
                select 1;
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("init.sql", lines(src), 2, CAP);

        assertEquals(1, block.start());
        assertEquals(3, block.end());
    }

    @Test
    void aHashAtLineStartIsACommentButTempTableNamesAreNot() {
        String src = """
                # 说明
                create table #temp (
                    id int
                );
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("init.sql", lines(src), 2, CAP);

        assertEquals(2, block.start());
        assertEquals(4, block.end());
    }

    @Test
    void aLineBetweenStatementsHasNoBlock() {
        String src = """
                select 1;

                -- 下一条
                select 2;
                """;

        assertNull(CodeBlocks.enclosing("init.sql", lines(src), 3, CAP));
    }

    @Test
    void filesWithoutBlockBoundariesHaveNoBlock() {
        assertNull(CodeBlocks.enclosing("app.properties", lines("a=1\nb=2"), 1, CAP));
    }

    @Test
    void aMapperStatementIsTheUnitNotTheWholeMapper() {
        String src = """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="x">
                  <select id="find">
                    select *
                    from t
                    <if test="a != null">
                      where a = #{a}
                    </if>
                  </select>
                </mapper>
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("UserMapper.xml", lines(src), 7, CAP);

        assertEquals(3, block.start());
        assertEquals(9, block.end());
        assertEquals("元素 select", block.label());
    }

    @Test
    void tagsInsideCommentsDoNotCount() {
        String src = """
                <mapper>
                  <!-- <select> -->
                  <select id="a">
                    select 1
                  </select>
                </mapper>
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.xml", lines(src), 4, CAP);

        assertEquals(3, block.start());
        assertEquals(5, block.end());
        assertEquals("元素 select", block.label());
    }

    @Test
    void aFileWhoseOnlyElementIsTheRootStillReturnsIt() {
        String src = """
                <mapper>
                  text
                </mapper>
                """;

        CodeBlocks.Block block = CodeBlocks.enclosing("A.xml", lines(src), 2, CAP);

        assertEquals(1, block.start());
        assertEquals(3, block.end());
    }

    private static String[] lines(String text) {
        String[] all = text.split("\n", -1);
        if (all.length > 0 && all[all.length - 1].isEmpty()) {
            return Arrays.copyOf(all, all.length - 1);
        }
        return all;
    }
}
