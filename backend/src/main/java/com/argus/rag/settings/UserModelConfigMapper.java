package com.argus.rag.settings;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 模型配置 Mapper。查询语义：
 * <ul>
 *   <li>{@link #findByUserId}：用户级覆盖行（不含平台行）；</li>
 *   <li>{@link #findPlatform}：平台默认行（user_id IS NULL，至多一条）。</li>
 * </ul>
 */
@Mapper
public interface UserModelConfigMapper extends BaseMapper<UserModelConfigEntity> {

    @Select("SELECT * FROM user_model_config WHERE user_id = #{userId}")
    UserModelConfigEntity findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM user_model_config WHERE user_id IS NULL LIMIT 1")
    UserModelConfigEntity findPlatform();
}
