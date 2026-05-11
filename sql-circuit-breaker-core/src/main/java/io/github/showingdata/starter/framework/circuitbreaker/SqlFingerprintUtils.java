package io.github.showingdata.starter.framework.circuitbreaker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;
/**
 * @author chenjiang
 * @date 2026/5/1 16:23
 * @className SqlFingerprintUtils
 * @description SqlFingerprintUtils
 */
public class SqlFingerprintUtils {

    private SqlFingerprintUtils() {
    }

    // 字符串字面量必须最先替换，防止其内容（如 '--' 或 '/* */'）被误当作注释处理
    // 正则支持 SQL 标准转义：两个连续单引号 '' 表示一个单引号字符
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern SINGLE_LINE_COMMENT_PATTERN = Pattern.compile("--[^\\n]*");
    private static final Pattern HASH_COMMENT_PATTERN = Pattern.compile("#[^\\n]*");
    private static final Pattern MULTI_LINE_COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    // <foreach> 展开的 IN 子句长度随列表大小变化（如 IN (?,?) vs IN (?,?,?)），
    // 归一化为 IN (?) 确保同一逻辑查询产生相同指纹，防止熔断状态按列表大小碎片化。
    // 仅匹配纯占位符 IN 子句（只含 ?、,、空白），不影响 IN (subquery) 场景。
    private static final Pattern IN_CLAUSE_PATTERN = Pattern.compile("\\bin\\s*\\([\\s?,]+\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 提取 SQL 指纹：替换字符串字面量 → 去注释 → 小写 → 替换数字为 ? → 归一化 IN 子句 → 合并空白。
     * 参数值不同但结构相同的 SQL 产生相同指纹，作为熔断匹配单位。
     */
    public static String extract(String sql) {
        if (sql == null) {
            return "";
        }
        // 1. 先替换字符串字面量，避免其内容干扰后续注释剥离
        String s = STRING_LITERAL_PATTERN.matcher(sql).replaceAll("?");
        // 2. 去注释
        s = SINGLE_LINE_COMMENT_PATTERN.matcher(s).replaceAll("");
        s = HASH_COMMENT_PATTERN.matcher(s).replaceAll("");
        s = MULTI_LINE_COMMENT_PATTERN.matcher(s).replaceAll("");
        // 3. 统一小写，替换数字，归一化 IN 子句，合并空白
        s = s.toLowerCase().trim();
        s = NUMBER_PATTERN.matcher(s).replaceAll("?");
        s = IN_CLAUSE_PATTERN.matcher(s).replaceAll("in (?)");
        return WHITESPACE_PATTERN.matcher(s).replaceAll(" ");
    }

    /**
     * 对已提取的 SQL 指纹取 MD5，用作熔断 Key。固定 32 位，避免超长 SQL 撑大 Key。
     * 调用方先调 extract() 得到指纹（用于日志），再调此方法得到 Key，避免重复计算。
     */
    public static String hash(String fingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 是 JDK 标准算法，不会走到这里
            return fingerprint;
        }
    }

}
