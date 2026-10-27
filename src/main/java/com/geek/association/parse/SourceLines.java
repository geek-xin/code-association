package com.geek.association.parse;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按文件缓存的行切分结果，供各分析器"按方法取代码片段"时复用。
 *
 * 为什么需要：{@code ApiAnalyzer.requestBodyType} / {@code firstAnnotationValue} /
 * {@code methodBody}、{@code SqlAnalyzer.methodSnippet}、{@code ConfigAnalyzer.snippet}
 * 都是在"按方法循环"里调用一次、每次把整个文件 {@code split("\\R")} 一遍。
 * 一个文件有 N 个方法就切 N 次，代价 O(N × 文件大小)，模块代码量大时这一项会盖过真正的分析工作。
 * 同一个 {@code FileSource} 在一次分析里是只读快照，同一路径的内容不会变，所以一个文件切一次就够。
 * 这个前提由使用方式保证：每个 analyzer 实例只服务一次分析（一个 revision），缓存不跨实例共享，
 * 因此也不需要按内容或 hash 做失效判断。
 *
 * 上界：条目数与估算占用双上限，超出后按 LRU 淘汰。
 * 占用不能只按源字符数算 —— {@code split} 出来的每一行都是一个独立 String，
 * 除内容外还有对象头 + 字符数组头 + 数组引用的固定开销，所以按
 * {@code 字符数 + 行数 × 48 字节} 估算（48 覆盖 16 字节对象头、16 字节数组头与对齐）。
 * 32MB 的上限约为常见仓库源码规模的数倍：既能装下本轮的热文件（每个 analyzer 只碰
 * Java 类型所在的那批文件，工作集通常远小于此），又不会让一次分析多占几十 MB。
 * 超过单文件上限的巨型文件只切不缓存 —— 缓存它会把其它文件全挤出去，命中率反而更差。
 */
final class SourceLines {

    /** 一次分析最多保留的估算字节数。 */
    private static final long MAX_CACHED_BYTES = 32L * 1024 * 1024;
    /** 条目数上限，防止大量小文件把 map 结构本身撑大。 */
    private static final int MAX_ENTRIES = 512;
    /** 单文件缓存上限：超过它的文件（生成的巨型类、打包进来的第三方源码）只切不缓存。 */
    private static final int MAX_CACHEABLE_CHARS = 4 * 1024 * 1024;
    /** 每行 String 的固定开销估算：对象头 + 字符数组头 + 对齐。 */
    private static final int PER_LINE_OVERHEAD = 48;

    private record LineSet(String[] lines, long bytes) {
    }

    private final Map<String, LineSet> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long cachedBytes;

    /**
     * 按 {@code \R} 切分并缓存，语义与原 {@code content.split("\\R", -1)} 完全一致（保留尾部空行）。
     *
     * @param filePath 缓存键；为 null 时退化为每次直接切分
     * @param content  该文件的全文，必须与 filePath 对应
     */
    String[] lines(String filePath, String content) {
        if (filePath == null) {
            return split(content);
        }
        LineSet hit = cache.get(filePath);
        if (hit != null) {
            return hit.lines();
        }
        String[] lines = split(content);
        if (content.length() <= MAX_CACHEABLE_CHARS) {
            long bytes = content.length() + (long) lines.length * PER_LINE_OVERHEAD;
            cache.put(filePath, new LineSet(lines, bytes));
            cachedBytes += bytes;
            evictIfNeeded();
        }
        return lines;
    }

    /** 按插入顺序（即最久未使用优先，accessOrder=true 时迭代顺序就是 LRU 顺序）淘汰到限额以内。 */
    private void evictIfNeeded() {
        Iterator<Map.Entry<String, LineSet>> it = cache.entrySet().iterator();
        while ((cache.size() > MAX_ENTRIES || cachedBytes > MAX_CACHED_BYTES) && it.hasNext()) {
            Map.Entry<String, LineSet> eldest = it.next();
            cachedBytes -= eldest.getValue().bytes();
            it.remove();
        }
    }

    private static String[] split(String content) {
        return content.split("\\R", -1);
    }
}
