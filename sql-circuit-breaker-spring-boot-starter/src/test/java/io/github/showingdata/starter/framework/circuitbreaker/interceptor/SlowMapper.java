package io.github.showingdata.starter.framework.circuitbreaker.interceptor;

import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 开启 MyBatis 二级缓存（{@link CacheNamespace}）的测试 Mapper。
 * <p>
 * {@code SELECT SLOWFN(?)} 对所有入参共享同一 SQL 指纹（熔断 key 相同），
 * 但不同入参对应不同的二级缓存 Key——因此可以用「同参」制造缓存命中、「异参」制造真实落库，
 * 二者都归到同一个熔断器上，从而精确复现「缓存命中是否污染熔断计数」。
 */
@CacheNamespace
public interface SlowMapper {

    @Select("SELECT SLOWFN(#{ms})")
    Integer slow(@Param("ms") int ms);
}
